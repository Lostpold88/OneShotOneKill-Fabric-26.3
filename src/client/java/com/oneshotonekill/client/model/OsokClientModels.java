package com.oneshotonekill.client.model;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.registry.ModDataComponents;
import java.util.List;
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
import net.minecraft.util.Util;
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

   /**
    * Animiertes Mehrschichtmodell des Zeitverzerrers.
    *
    * <p>Das alte Modell hing alle 54 Leuchtflächen an denselben {@code DyedItemColor}. Dadurch
    * konnten sie nur gemeinsam hart ein- und ausgeschaltet werden, und jeder Wechsel musste als
    * Inventaränderung vom Server kommen. Hier bleiben Gehäuse und Zifferblatt eine ruhige
    * Basisschicht; jedes Ringsegment, Zeiger, Kern und jede der acht Linsen erhalten eigene
    * Renderlagen. Kein Bauteil wird räumlich gedreht: Die Bewegung entsteht ausschließlich aus
    * Lichtkaskaden, sodass die Taschenuhr stabil und massiv in der Hand bleibt.
    * Ihre Bewegung folgt absichtlich der monotonen Echtzeit und nicht den Spielticks – der
    * Zeitverzerrer läuft also gerade dann sichtbar weiter, wenn er den Server auf acht TPS
    * verlangsamt.</p>
    */
   public static final class ChronoDistorterModel implements ItemModel {
      private static final float CASCADE_SECONDS = 1.45F;
      private static final float SURGE_SECONDS = 2.40F;

      private final Part base;
      private final List<Part> innerRing;
      private final List<Part> outerRing;
      private final Part minuteHand;
      private final Part secondHand;
      private final Part core;
      private final List<Part> lenses;
      private final Matrix4fc baseTransform;

      private ChronoDistorterModel(Part base, List<Part> innerRing, List<Part> outerRing, Part minuteHand,
                                  Part secondHand, Part core, List<Part> lenses, Matrix4fc baseTransform) {
         this.base = base;
         this.innerRing = innerRing;
         this.outerRing = outerRing;
         this.minuteHand = minuteHand;
         this.secondHand = secondHand;
         this.core = core;
         this.lenses = lenses;
         this.baseTransform = baseTransform;
      }

      @Override
      public void update(ItemStackRenderState output, ItemStack item, ItemModelResolver resolver,
                         ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
         output.appendModelIdentityElement(this);
         double seconds = Util.getNanos() / 1_000_000_000.0;
         float surge = surge(seconds);
         TimeDistortionEffects chrono = TimeDistortionEffects.INSTANCE;
         float field = chrono.modelActivePower();
         float urgency = chrono.modelUrgency();
         float restoreFlash = chrono.modelRestoreFlash();
         float ignition = chrono.activationProgress();
         float urgentStrobe = urgency * Math.max(0.0F,
            (float) Math.sin(seconds * Math.PI * (5.0 + urgency * 7.0)));

         addLayer(output, this.base, displayContext, this.baseTransform, null);

         // Die Ringe stehen geometrisch fest. Je zwei dicht aufeinanderfolgende Lichtfronten
         // laufen in entgegengesetzter Richtung darüber; der periodische Vollimpuls verbindet
         // anschließend alle Segmente für einen Moment zu einem weißglühenden Zeitfeld.
         float ringSpeed = 1.0F + field * 1.35F + urgency * 2.8F;
         float ringCycle = (float) (seconds / 1.18 * ringSpeed);
         for (int index = 0; index < this.innerRing.size(); index++) {
            float wave = doubleWave(ringCycle, index, this.innerRing.size(), false);
            float ignitionFlash = ignitionFlash(ignition, 0.34F + index / (float) this.innerRing.size() * 0.28F) * field;
            float power = Math.clamp(0.07F + wave * 0.93F + surge * 0.78F
               + field * 0.25F + ignitionFlash + urgentStrobe * 0.85F + restoreFlash, 0.0F, 1.0F);
            int colour = mixColour(0xFF10051F, 0xFFB45DFF, power);
            colour = mixColour(colour, 0xFFFFFFFF, Math.clamp(wave * 0.48F + surge * 0.52F
               + ignitionFlash * 0.75F + urgentStrobe * 0.55F + restoreFlash, 0.0F, 1.0F));
            addLayer(output, this.innerRing.get(index), displayContext, this.baseTransform, colour);
         }
         for (int index = 0; index < this.outerRing.size(); index++) {
            float wave = doubleWave(ringCycle * 0.82F + 0.19F, index, this.outerRing.size(), true);
            float ignitionFlash = ignitionFlash(ignition, 0.04F + index / (float) this.outerRing.size() * 0.28F) * field;
            float power = Math.clamp(0.06F + wave * 0.94F + surge * 0.82F
               + field * 0.28F + ignitionFlash + urgentStrobe * 0.92F + restoreFlash, 0.0F, 1.0F);
            int colour = mixColour(0xFF03101D, 0xFF35DFFF, power);
            colour = mixColour(colour, 0xFFFFFFFF, Math.clamp(wave * 0.55F + surge * 0.45F
               + ignitionFlash * 0.78F + urgentStrobe * 0.62F + restoreFlash, 0.0F, 1.0F));
            addLayer(output, this.outerRing.get(index), displayContext, this.baseTransform, colour);
         }

         // Auch die Zeiger bleiben fest. Der Sekundenzeiger antwortet stattdessen mit einem
         // markanten Doppelblitz auf jeden Kernschlag.
         addLayer(output, this.minuteHand, displayContext, this.baseTransform, null);
         float handFlash = doubleBeat(seconds, 0.92F);
         float handPower = Math.clamp(0.16F + handFlash * 0.84F + surge * 0.72F
            + field * 0.30F + urgentStrobe + restoreFlash, 0.0F, 1.0F);
         addLayer(output, this.secondHand, displayContext, this.baseTransform,
            mixColour(0xFF122653, 0xFFFFFFFF, handPower));

         float breath = 0.5F + 0.5F * (float) Math.sin(seconds * Math.PI * 2.0 / 1.8);
         float heartbeat = doubleBeat(seconds, 1.15F);
         float coreIgnition = ignitionFlash(ignition, 0.86F) * field;
         float corePower = Math.clamp(0.22F + breath * 0.24F + heartbeat * 0.72F + surge * 0.72F
            + field * 0.34F + coreIgnition + urgentStrobe + restoreFlash, 0.0F, 1.0F);
         addLayer(output, this.core, displayContext, this.baseTransform,
            mixColour(0xFF241044, 0xFFFFFFFF, corePower));

         float cascade = (float) (seconds / CASCADE_SECONDS);
         for (int index = 0; index < this.lenses.size(); index++) {
            float phase = fractional(cascade - index / (float) this.lenses.size());
            float distance = Math.min(phase, 1.0F - phase);
            float wave = Math.clamp(1.0F - distance * 7.0F, 0.0F, 1.0F);
            wave *= wave;
            float ignitionFlash = ignitionFlash(ignition, 0.64F + index / (float) this.lenses.size() * 0.20F) * field;
            float power = Math.clamp(0.08F + wave * 0.92F + surge * 0.72F
               + field * 0.30F + ignitionFlash + urgentStrobe + restoreFlash, 0.0F, 1.0F);
            int coldToViolet = mixColour(0xFF080E28, 0xFF9E56FF, power);
            int colour = mixColour(coldToViolet, 0xFFFFFFFF, Math.clamp(wave * 0.58F + surge * 0.42F
               + ignitionFlash * 0.76F + urgentStrobe * 0.65F + restoreFlash, 0.0F, 1.0F));
            addLayer(output, this.lenses.get(index), displayContext, this.baseTransform, colour);
         }

         // GUI- und Picture-in-Picture-Renderer dürfen den Zustand nicht als unverändert cachen.
         output.appendModelIdentityElement((long) (seconds * 60.0));
         output.setAnimated();
      }

      private static float doubleWave(float cycle, int index, int count, boolean reverse) {
         float position = index / (float) count;
         float direction = reverse ? -1.0F : 1.0F;
         float primary = wave(fractional(cycle * direction - position), count);
         float echo = wave(fractional((cycle - 0.115F) * direction - position), count) * 0.62F;
         return Math.max(primary, echo);
      }

      private static float wave(float phase, int count) {
         float distance = Math.min(phase, 1.0F - phase);
         float value = Math.clamp(1.0F - distance * count * 0.72F, 0.0F, 1.0F);
         return value * value;
      }

      private static float doubleBeat(double seconds, float period) {
         float phase = fractional((float) (seconds / period));
         float first = beatAt(phase, 0.0F, 0.075F);
         float second = beatAt(phase, 0.18F, 0.065F) * 0.72F;
         return Math.max(first, second);
      }

      private static float beatAt(float phase, float centre, float width) {
         float distance = Math.abs(phase - centre);
         distance = Math.min(distance, 1.0F - distance);
         float value = Math.clamp(1.0F - distance / width, 0.0F, 1.0F);
         return value * value;
      }

      /** Kurzer Lichtstoß, wenn die Aktivierungsfront dieses unbewegte Bauteil erreicht. */
      private static float ignitionFlash(float progress, float centre) {
         float distance = (progress - centre) / 0.075F;
         return (float) Math.exp(-distance * distance);
      }

      private static void addLayer(ItemStackRenderState output, Part part, ItemDisplayContext context,
                                   Matrix4fc transform, @Nullable Integer tint) {
         ItemStackRenderState.LayerRenderState layer = output.newLayer();
         layer.setExtents(part.extents);
         layer.setLocalTransform(transform);
         part.properties.applyToLayer(layer, context);
         if (tint != null) {
            layer.tintLayers().add(tint);
         }
         layer.prepareQuadList().addAll(part.quads.getAll());
      }

      private static float surge(double seconds) {
         float phase = fractional((float) (seconds / SURGE_SECONDS));
         float distance = Math.min(phase, 1.0F - phase);
         return (float) Math.exp(-(distance * distance) / 0.0028F);
      }

      private static float fractional(float value) {
         return value - (float) Math.floor(value);
      }

      private static int mixColour(int from, int to, float amount) {
         float t = Math.clamp(amount, 0.0F, 1.0F);
         int red = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
         int green = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
         int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
         return 0xFF000000 | red << 16 | green << 8 | blue;
      }

      private record Part(QuadCollection quads, ModelRenderProperties properties,
                          Supplier<Vector3fc[]> extents) {
      }

      public record Unbaked(Identifier base, List<Identifier> innerRing, List<Identifier> outerRing,
                            Identifier minuteHand, Identifier secondHand, Identifier core,
                            List<Identifier> lenses) implements ItemModel.Unbaked {
         public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("base").forGetter(Unbaked::base),
            Identifier.CODEC.listOf().fieldOf("inner_ring").forGetter(Unbaked::innerRing),
            Identifier.CODEC.listOf().fieldOf("outer_ring").forGetter(Unbaked::outerRing),
            Identifier.CODEC.fieldOf("minute_hand").forGetter(Unbaked::minuteHand),
            Identifier.CODEC.fieldOf("second_hand").forGetter(Unbaked::secondHand),
            Identifier.CODEC.fieldOf("core").forGetter(Unbaked::core),
            Identifier.CODEC.listOf().fieldOf("lenses").forGetter(Unbaked::lenses)
         ).apply(instance, Unbaked::new));

         @Override
         public MapCodec<Unbaked> type() {
            return MAP_CODEC;
         }

         @Override
         public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.markDependency(this.base);
            this.innerRing.forEach(resolver::markDependency);
            this.outerRing.forEach(resolver::markDependency);
            resolver.markDependency(this.minuteHand);
            resolver.markDependency(this.secondHand);
            resolver.markDependency(this.core);
            this.lenses.forEach(resolver::markDependency);
         }

         @Override
         public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            ModelBaker baker = context.blockModelBaker();
            List<Part> bakedInnerRing = this.innerRing.stream().map(id -> bakePart(baker, id)).toList();
            List<Part> bakedOuterRing = this.outerRing.stream().map(id -> bakePart(baker, id)).toList();
            List<Part> bakedLenses = this.lenses.stream().map(id -> bakePart(baker, id)).toList();
            if (bakedInnerRing.size() != 12 || bakedOuterRing.size() != 12 || bakedLenses.size() != 8) {
               throw new IllegalArgumentException("Der Zeitverzerrer braucht 12 + 12 Ringsegmente und acht Kondensatorlinsen");
            }
            return new ChronoDistorterModel(
               bakePart(baker, this.base),
               bakedInnerRing,
               bakedOuterRing,
               bakePart(baker, this.minuteHand),
               bakePart(baker, this.secondHand),
               bakePart(baker, this.core),
               bakedLenses,
               transformation);
         }

         private static Part bakePart(ModelBaker baker, Identifier id) {
            ResolvedModel resolved = baker.getModel(id);
            TextureSlots slots = resolved.getTopTextureSlots();
            QuadCollection quads = resolved.bakeTopGeometry(slots, baker, BlockModelRotation.IDENTITY);
            ModelRenderProperties properties = ModelRenderProperties.fromResolvedModel(baker, resolved, slots);
            Supplier<Vector3fc[]> extents = Suppliers.memoize(() -> CuboidItemModelWrapper.computeExtents(quads.getAll()));
            return new Part(quads, properties, extents);
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
