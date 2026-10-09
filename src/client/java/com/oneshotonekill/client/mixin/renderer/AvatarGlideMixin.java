package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.renderer.GliderWingRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zeichnet fliegende Spieler in der Elytra-Haltung: waagerecht in Blickrichtung.
 * <p>
 * Die Entity selbst fliegt nicht per Elytra (das Flag würde deren Physik einschalten), also wird
 * nur der Renderzustand umgeschrieben. Alles hängt am Liegegrad aus {@link GliderWingRenderer#lie}:
 * Er ist beim Abheben und - wichtiger - beim Landen weich, und Körper, Gliedmaßen und Geschirr
 * lesen denselben Wert. {@code fallFlyingTimeInTicks} wird aus ihm zurückgerechnet, denn Vanilla
 * macht daraus {@code t^2 / 100} als Stärke der Elytra-Drehung.
 * <p>
 * Der Körper wird auf die Blickrichtung gezogen: Vanilla lässt ihn der Bewegungsrichtung nur träge
 * folgen und dreht den Kopf frei dagegen, die Figur hing dann hinten fest und schwang vorn hin
 * und her. Der Kopf behält deshalb nur den Anteil seiner Drehung, den der Körper nicht übernimmt.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarGlideMixin {
   @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
           at = @At("RETURN"))
   private void osok$glideStance(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
      float lie = GliderWingRenderer.lie(entity.getUUID(), partialTicks);
      if (lie <= 0.001F) {
         return;
      }
      state.setData(GliderWingRenderer.LIE, lie);
      state.isFallFlying = true;
      state.fallFlyingTimeInTicks = 10.0F * (float) Math.sqrt(lie);
      state.bodyRot += state.yRot * lie;
      state.yRot *= 1.0F - lie;
      state.flyingYRot *= 1.0F - lie;

      // Das Geschirr ist das Gerät, nicht das, was in der Hand liegt: im Flug bleiben die Hände leer.
      if (lie > 0.5F && !state.isUsingItem) {
         state.leftHandItemStack = ItemStack.EMPTY;
         state.rightHandItemStack = ItemStack.EMPTY;
         state.leftHandItemState.clear();
         state.rightHandItemState.clear();
         state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
         state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
      }
   }
}
