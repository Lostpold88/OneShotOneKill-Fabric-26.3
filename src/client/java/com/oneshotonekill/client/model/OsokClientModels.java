package com.oneshotonekill.client.model;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.registry.ModDataComponents;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Sammelklasse aller benutzerdefinierten Client-Itemmodelle und Modell-Bedingungen.
 */
public final class OsokClientModels {
   private OsokClientModels() {
   }

   public static final class SpinningRotorModel implements ItemModel {
      private final QuadCollection quads;
      private final ModelRenderProperties properties;
      private final Matrix4fc baseTransform;
      private final float pivotX;
      private final float pivotY;
      private final Supplier<Vector3fc[]> extents;
   
      private SpinningRotorModel(
         QuadCollection quads,
         ModelRenderProperties properties,
         Matrix4fc baseTransform,
         float pivotX,
         float pivotY
      ) {
         this.quads = quads;
         this.properties = properties;
         this.baseTransform = baseTransform;
         this.pivotX = pivotX;
         this.pivotY = pivotY;
         this.extents = Suppliers.memoize(() -> CuboidItemModelWrapper.computeExtents(quads.getAll()));
      }
   
      @Override
      public void update(
         ItemStackRenderState output,
         ItemStack item,
         ItemModelResolver resolver,
         ItemDisplayContext displayContext,
         ClientLevel level,
         ItemOwner owner,
         int seed
      ) {
         output.appendModelIdentityElement(this);
         float angle = spinAngle(displayContext, owner);
   
         ItemStackRenderState.LayerRenderState layer = output.newLayer();
         layer.setExtents(this.extents);
         layer.setLocalTransform(spun(angle));
         this.properties.applyToLayer(layer, displayContext);
         layer.prepareQuadList().addAll(this.quads.getAll());
   
         if (angle != 0.0F) {
            // Ohne diese Kennzeichnung hielte Vanilla das Bild für unverändert und zeigte es eingefroren.
            output.appendModelIdentityElement(angle);
            output.setAnimated();
         }
         if (this.quads.hasMaterialFlag(2)) {
            output.setAnimated();
         }
      }
   
      /** Drehung um die Längsachse, aufgesetzt auf die vom Elternmodell geerbte Transformation. */
      private Matrix4fc spun(float angle) {
         Matrix4f matrix = new Matrix4f(this.baseTransform);
         if (angle != 0.0F) {
            matrix.translate(this.pivotX, this.pivotY, 0.0F)
               .rotateZ(angle)
               .translate(-this.pivotX, -this.pivotY, 0.0F);
         }
         return matrix;
      }
   
      private static float spinAngle(ItemDisplayContext displayContext, ItemOwner owner) {
         if (displayContext == ItemDisplayContext.GUI || owner == null) {
            return 0.0F;
         }
         LivingEntity holder = owner.asLivingEntity();
         if (holder == null) {
            return 0.0F;
         }
         float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
         return MinigunSpinState.INSTANCE.getPhase(holder, partialTick);
      }
   
      /**
       * @param model   das zu drehende Modell
       * @param pivotX  Drehachse in Modelleinheiten (0 bis 16)
       * @param pivotY  Drehachse in Modelleinheiten (0 bis 16)
       */
      public record Unbaked(Identifier model, float pivotX, float pivotY) implements ItemModel.Unbaked {
         public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("model").forGetter(Unbaked::model),
            Codec.FLOAT.optionalFieldOf("pivot_x", 8.0F).forGetter(Unbaked::pivotX),
            Codec.FLOAT.optionalFieldOf("pivot_y", 8.0F).forGetter(Unbaked::pivotY)
         ).apply(instance, Unbaked::new));
   
         @Override
         public MapCodec<Unbaked> type() {
            return MAP_CODEC;
         }
   
         @Override
         public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.markDependency(this.model);
         }
   
         @Override
         public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            ModelBaker baker = context.blockModelBaker();
            ResolvedModel resolved = baker.getModel(this.model);
            TextureSlots slots = resolved.getTopTextureSlots();
            QuadCollection baked = resolved.bakeTopGeometry(slots, baker, BlockModelRotation.IDENTITY);
            ModelRenderProperties properties = ModelRenderProperties.fromResolvedModel(baker, resolved, slots);
            // Die Modell-Koordinaten laufen beim Zeichnen von 0 bis 1, nicht von 0 bis 16.
            return new SpinningRotorModel(baked, properties, transformation, this.pivotX / 16.0F, this.pivotY / 16.0F);
         }
      }
   }

   public record HasPlacedC4Property() implements ConditionalItemModelProperty {
      public static final HasPlacedC4Property INSTANCE = new HasPlacedC4Property();
      public static final MapCodec<HasPlacedC4Property> MAP_CODEC = MapCodec.unit(INSTANCE);
   
      @Override
      public boolean get(
         ItemStack itemStack,
         @Nullable ClientLevel level,
         @Nullable LivingEntity owner,
         int seed,
         ItemDisplayContext displayContext
      ) {
         return itemStack.has(ModDataComponents.C4_ARMED);
      }
   
      @Override
      public MapCodec<HasPlacedC4Property> type() {
         return MAP_CODEC;
      }
   }
}
