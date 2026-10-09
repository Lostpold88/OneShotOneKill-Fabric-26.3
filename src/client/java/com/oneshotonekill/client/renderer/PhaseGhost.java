package com.oneshotonekill.client.renderer;

import com.oneshotonekill.shared.PhaseFields;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Macht die Blöcke der eigenen Phasen-Kugel halbtransparent – dieselben Blöcke, dieselbe Textur.
 * <p>
 * Der Weg läuft über Fabrics Modell-API und nicht über Mixins in den Abschnittsbauer. Sodium,
 * ImmediatelyFast und Indigo ersetzen oder umgehen {@code SectionCompiler} und {@code BufferBuilder};
 * jeder Haken dort greift nur bei einem von ihnen. Dagegen fragen alle Block-Renderer – Vanilla,
 * Indigo und Sodium – dasselbe Modell über {@code emitQuads}. Jedes Block-Modell wird deshalb
 * einmal beim Backen umhüllt, und die Hülle entscheidet erst beim Zeichnen anhand der Position:
 * liegt der Block in der Kugel des lokalen Spielers, geht jedes Quad in die durchscheinende Schicht
 * und mit gesenktem Alpha in den Puffer.
 * <p>
 * Die Tabelle {@link PhaseFields#CLIENT} enthält alle Kugeln, geprüft wird aber nur die des lokalen
 * Spielers – für alle anderen bleibt die Wand, was sie ist.
 */
public final class PhaseGhost {
   /** Deckkraft der Blöcke in der Kugel, 0..255. */
   public static final int ALPHA = 70;

   private static final QuadTransform FADE = PhaseGhost::fade;

   /** Der lokale Spieler; die Chunk-Arbeitsfäden dürfen {@code Minecraft#player} nicht selbst anfassen. */
   private static volatile UUID local;

   private PhaseGhost() {
   }

   public static void register() {
      ClientTickEvents.END_CLIENT_TICK.register(client -> local = client.player == null ? null : client.player.getUUID());
      ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake()
         .register(ModelModifier.WRAP_LAST_PHASE, (model, ignored) -> new Ghostable(model)));
   }

   private static boolean fade(MutableQuadView quad) {
      quad.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
      for (int vertex = 0; vertex < 4; vertex++) {
         int colour = quad.color(vertex);
         quad.color(vertex, ((colour >>> 24) * ALPHA / 255) << 24 | (colour & 0x00FFFFFF));
      }
      return true;
   }

   /** Reicht alles an das umhüllte Modell weiter und fügt nur für Kugelblöcke die Abdunklung ein. */
   private record Ghostable(BlockStateModel inner) implements BlockStateModel {
      @Override
      public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
         inner.collectParts(random, output);
      }

      @Override
      public Material.Baked particleMaterial() {
         return inner.particleMaterial();
      }

      @Override
      public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
         return inner.particleMaterial(level, pos, state);
      }

      @Override
      @BakedQuad.MaterialFlags
      public int materialFlags() {
         return inner.materialFlags();
      }

      @Override
      @BakedQuad.MaterialFlags
      public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
         int flags = inner.materialFlags(level, pos, state, random);
         return PhaseFields.CLIENT.coversFor(local, pos) ? flags | BakedQuad.FLAG_TRANSLUCENT : flags;
      }

      @Override
      public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                                RandomSource random) {
         // Ein zwischengespeicherter Schlüssel kennt die Kugel nicht; für ihre Blöcke gibt es keinen.
         return PhaseFields.CLIENT.coversFor(local, pos) ? null : inner.createGeometryKey(level, pos, state, random);
      }

      @Override
      public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                            RandomSource random, Predicate<@Nullable Direction> cullTest) {
         if (!PhaseFields.CLIENT.coversFor(local, pos)) {
            inner.emitQuads(emitter, level, pos, state, random, cullTest);
            return;
         }
         emitter.pushTransform(FADE);
         try {
            inner.emitQuads(emitter, level, pos, state, random, cullTest);
         } finally {
            emitter.popTransform();
         }
      }
   }
}
