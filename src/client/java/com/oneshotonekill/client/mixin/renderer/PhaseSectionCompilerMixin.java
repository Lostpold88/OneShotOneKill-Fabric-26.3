package com.oneshotonekill.client.mixin.renderer;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.oneshotonekill.client.renderer.PhaseGhost;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zeichnet die Blöcke einer Phasen-Kugel halbtransparent – dieselben Blöcke, dieselbe Textur.
 * <p>
 * Der Haken sitzt bewusst nicht am Aufruf von {@code tesselateBlock}: Fabrics Renderer-API
 * ersetzt ihn per {@code @Redirect} durch ihren eigenen Block-Renderer und lässt den übergebenen
 * Ausgang links liegen. Allen Pfaden gemeinsam sind dagegen drei Stellen:
 * <ol>
 *   <li>Die Schleife fragt je Block {@code getBlockState} – dort wird {@link PhaseGhost} gesetzt.</li>
 *   <li>{@code getOrBeginLayer} wählt die Schicht – für Kugelblöcke immer die durchscheinende,
 *       und der Puffer merkt sich, dass er abdunkeln soll ({@code PhaseBufferBuilderMixin}).</li>
 *   <li>{@code Block.shouldRenderFace} – siehe {@code PhaseShouldRenderFaceMixin}.</li>
 * </ol>
 * Außerdem gelten Kugelblöcke nicht als undurchsichtig für die Sichtbarkeitsprüfung, sonst würden
 * Abschnitte hinter der Wand gar nicht erst gezeichnet. Dass die Abschnitte neu gebaut werden,
 * sorgt der Handler des {@code PhaseFieldSystem.Sync}.
 */
@Mixin(SectionCompiler.class)
public abstract class PhaseSectionCompilerMixin {
   @WrapOperation(method = "compile", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
   private BlockState osok$enterBlock(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
      PhaseGhost.enter(pos);
      return original.call(region, pos);
   }

   @Inject(method = "compile", at = @At("RETURN"))
   private void osok$leaveSection(CallbackInfoReturnable<SectionCompiler.Results> cir) {
      PhaseGhost.leave();
   }

   @ModifyVariable(method = "getOrBeginLayer", at = @At("HEAD"), argsOnly = true)
   private ChunkSectionLayer osok$ghostLayer(ChunkSectionLayer layer) {
      return PhaseGhost.isGhost() ? ChunkSectionLayer.TRANSLUCENT : layer;
   }

   @ModifyReturnValue(method = "getOrBeginLayer", at = @At("RETURN"))
   private BufferBuilder osok$tagBuilder(BufferBuilder builder) {
      ((PhaseGhost.Builder) builder).osok$setGhost(PhaseGhost.isGhost());
      return builder;
   }

   @WrapOperation(method = "compile", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/chunk/VisGraph;setOpaque(Lnet/minecraft/core/BlockPos;)V"))
   private void osok$ghostNotOpaque(VisGraph graph, BlockPos pos, Operation<Void> original) {
      if (!PhaseGhost.isGhost()) {
         original.call(graph, pos);
      }
   }
}
