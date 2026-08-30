package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState.RenderPose;
import com.oneshotonekill.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Formt Vanillas zweihändige Bogenhaltung zu einer einarmigen Spider-Man-Pose um.
 * <p>
 * <p>Der Einstieg liegt nach {@link HumanoidModel#setupAnim(HumanoidRenderState)}, damit die
 * Richtung nicht von Geh-, Angriffs- oder Itemanimationen überschrieben wird. Weil auch die
 * Rüstungsmodelle durch dieselbe Methode laufen, bleiben Arm, Ärmel, Rüstung und Grappler exakt
 * deckungsgleich.</p>
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
   @Unique
   private static final float BODY_LEAN = 0.32F;
   @Unique
   private static final float TORSO_LENGTH = 12.0F;
   @Unique
   private static final float EXTENDED_ARM_X = (float) (-Math.PI / 2.0);
   @Unique
   private static final float FREE_ARM_X = 0.38F;
   @Unique
   private static final float FREE_ARM_Y = 0.48F;
   @Unique
   private static final float FREE_ARM_Z = 0.34F;

   @Inject(
      method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
      at = @At("RETURN"))
   private void osok$poseGrapplingPlayer(HumanoidRenderState state, CallbackInfo ci) {
      if (!(state instanceof AvatarRenderState avatar) || Minecraft.getInstance().level == null) {
         return;
      }
      Entity entity = Minecraft.getInstance().level.getEntity(avatar.id);
      if (!(entity instanceof LivingEntity living)) {
         return;
      }

      RenderPose aim = GrapplePullState.INSTANCE.aimPose(living,
         Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
      HumanoidArm grappleArm = grappleArm(state);
      if (aim == null || grappleArm == null) {
         return;
      }

      HumanoidModel<?> model = (HumanoidModel<?>)(Object)this;
      float blend = aim.blend();
      RenderPose pull = GrapplePullState.INSTANCE.pose(living,
         Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
      float wholeBodyTilt = pull == null ? 0.0F : pull.blend();
      float localElevation = (float)Math.toRadians(-aim.elevation() * (1.0F - wholeBodyTilt));

      float previousBodyRotation = model.body.xRot;
      model.body.xRot = Mth.rotLerpRad(blend, previousBodyRotation, BODY_LEAN);

      // Der Körperwürfel ist oben gelagert und endet zwölf Modelleinheiten tiefer an der Hüfte.
      // Beim Vorlehnen wandert dieses untere Ende nach hinten/oben. Die Bein-Pivots müssen
      // dieselbe Strecke mitgehen, sonst steht die Hüfte sichtbar hinter den Beinen.
      float hipOffsetY = TORSO_LENGTH * (Mth.cos(model.body.xRot) - Mth.cos(previousBodyRotation));
      float hipOffsetZ = TORSO_LENGTH * (Mth.sin(model.body.xRot) - Mth.sin(previousBodyRotation));
      model.rightLeg.y += hipOffsetY;
      model.leftLeg.y += hipOffsetY;
      model.rightLeg.z += hipOffsetZ;
      model.leftLeg.z += hipOffsetZ;

      model.head.xRot = Mth.rotLerpRad(blend, model.head.xRot, localElevation - BODY_LEAN * 0.25F);

      boolean rightHandedShot = grappleArm == HumanoidArm.RIGHT;
      ModelPart extendedArm = rightHandedShot ? model.rightArm : model.leftArm;
      ModelPart freeArm = rightHandedShot ? model.leftArm : model.rightArm;

      extendedArm.xRot = Mth.rotLerpRad(blend, extendedArm.xRot,
         EXTENDED_ARM_X + localElevation - BODY_LEAN * 0.15F);
      extendedArm.yRot = Mth.rotLerpRad(blend, extendedArm.yRot, rightHandedShot ? -0.08F : 0.08F);
      extendedArm.zRot = Mth.rotLerpRad(blend, extendedArm.zRot, 0.0F);

      freeArm.xRot = Mth.rotLerpRad(blend, freeArm.xRot, FREE_ARM_X + localElevation * 0.20F);
      freeArm.yRot = Mth.rotLerpRad(blend, freeArm.yRot, rightHandedShot ? -FREE_ARM_Y : FREE_ARM_Y);
      freeArm.zRot = Mth.rotLerpRad(blend, freeArm.zRot, rightHandedShot ? -FREE_ARM_Z : FREE_ARM_Z);
   }

   @Unique
   private static @Nullable HumanoidArm grappleArm(HumanoidRenderState state) {
      boolean right = state.rightHandItemStack.is(ModItems.GRAPPLING_HOOK);
      boolean left = state.leftHandItemStack.is(ModItems.GRAPPLING_HOOK);
      if (!right && !left) {
         return null;
      }
      if (right && left) {
         return state.mainArm;
      }
      return right ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
   }
}
