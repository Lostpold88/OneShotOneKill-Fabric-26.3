package com.oneshotonekill.client.mixin.renderer;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.oneshotonekill.client.renderer.PhaseGhost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Senkt die Deckkraft der Vertexfarbe, solange der Puffer Kugelblöcke aufnimmt.
 * <p>
 * Welche Quads halbtransparent sein sollen, entscheidet {@code PhaseSectionCompilerMixin}; hier
 * kommt der Wert an, egal ob der Vanilla-Pfad die Farbe als Ganzes setzt oder Fabrics Pfad sie
 * in Bytes schreibt. Das Merkmal hängt am Puffer, nicht am Faden, damit die Zeichenaufrufe der
 * ganzen Spielwelt keinen Fadenzugriff bezahlen.
 */
@Mixin(BufferBuilder.class)
public abstract class PhaseBufferBuilderMixin implements PhaseGhost.Builder {
   @Unique
   private boolean osok$ghost;

   @Override
   public void osok$setGhost(boolean ghost) {
      this.osok$ghost = ghost;
   }

   @ModifyVariable(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
   private int osok$fadeVertex(int color) {
      return osok$ghost ? PhaseGhost.fade(color) : color;
   }

   @ModifyVariable(method = "setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"),
      argsOnly = true, ordinal = 0)
   private int osok$fadeColor(int color) {
      return osok$ghost ? PhaseGhost.fade(color) : color;
   }

   @ModifyVariable(method = "setColor(IIII)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"),
      argsOnly = true, ordinal = 3)
   private int osok$fadeAlpha(int alpha) {
      return osok$ghost ? alpha * PhaseGhost.ALPHA / 255 : alpha;
   }
}
