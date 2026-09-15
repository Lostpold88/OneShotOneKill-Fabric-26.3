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
import net.minecraft.world.InteractionHand;
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

    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("RETURN"))
    private void osok$poseGrapplingPlayer(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar) || Minecraft.getInstance().level == null) {
            return;
        }
        Entity entity = Minecraft.getInstance().level.getEntity(avatar.id);
        if (!(entity instanceof LivingEntity living) || com.oneshotonekill.client.effect.BoogieBombClient.isDancing(entity.getUUID())) {
            return;
        }

        RenderPose aim = GrapplePullState.INSTANCE.aimPose(living,
                Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        HumanoidArm grappleArm = grappleArm(state);
        if (aim == null || grappleArm == null) {
            return;
        }

        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        float blend = aim.blend();
        RenderPose pull = GrapplePullState.INSTANCE.pose(living,
                Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        float wholeBodyTilt = pull == null ? 0.0F : pull.blend();
        float localElevation = (float) Math.toRadians(-aim.elevation() * (1.0F - wholeBodyTilt));

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

        if (pull != null && pull.blend() > 0.001F) {
            float pullWeight = pull.blend();
            // Dynamische Flieger-Silhouette: Beine strecken/anwinkeln statt in der Luft zu laufen
            float flightLegRightX = 0.65F;
            float flightLegLeftX = 0.28F;
            float flightLegSpread = 0.12F;

            model.rightLeg.xRot = Mth.rotLerpRad(pullWeight, model.rightLeg.xRot, flightLegRightX);
            model.rightLeg.yRot = Mth.rotLerpRad(pullWeight, model.rightLeg.yRot, 0.0F);
            model.rightLeg.zRot = Mth.rotLerpRad(pullWeight, model.rightLeg.zRot, flightLegSpread);

            model.leftLeg.xRot = Mth.rotLerpRad(pullWeight, model.leftLeg.xRot, flightLegLeftX);
            model.leftLeg.yRot = Mth.rotLerpRad(pullWeight, model.leftLeg.yRot, 0.0F);
            model.leftLeg.zRot = Mth.rotLerpRad(pullWeight, model.leftLeg.zRot, -flightLegSpread);

            // Freier Arm geht in dynamische Flug-Balance-Haltung (aerodynamisch nach hinten-außen)
            float flightArmX = 0.85F;
            float flightArmY = rightHandedShot ? 0.25F : -0.25F;
            float flightArmZ = rightHandedShot ? -0.55F : 0.55F;
            freeArm.xRot = Mth.rotLerpRad(pullWeight, freeArm.xRot, flightArmX);
            freeArm.yRot = Mth.rotLerpRad(pullWeight, freeArm.yRot, flightArmY);
            freeArm.zRot = Mth.rotLerpRad(pullWeight, freeArm.zRot, flightArmZ);
        }
    }

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("RETURN"))
    private void osok$poseMantlingPlayer(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar) || Minecraft.getInstance().level == null) return;
        Entity entity = Minecraft.getInstance().level.getEntity(avatar.id);
        if (entity == null || com.oneshotonekill.client.effect.BoogieBombClient.isDancing(entity.getUUID())) return;
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float blend = com.oneshotonekill.client.movement.ClientClimbing.INSTANCE.pose(entity, partialTick);
        if (blend <= 0) return;
        float stroke = com.oneshotonekill.client.movement.ClientClimbing.INSTANCE.stroke(entity, partialTick);
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;

        HumanoidArm swingingArm = (avatar.swingAnimation > 0 && avatar.currentSwing != null)
                ? (avatar.currentSwing.hand() == InteractionHand.MAIN_HAND ? avatar.mainArm : avatar.mainArm.getOpposite())
                : null;

        boolean rightArmCombat = (swingingArm == HumanoidArm.RIGHT)
                || avatar.rightArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW
                || avatar.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_CHARGE
                || avatar.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD
                || avatar.rightArmPose == HumanoidModel.ArmPose.BLOCK;

        boolean leftArmCombat = (swingingArm == HumanoidArm.LEFT)
                || avatar.leftArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW
                || avatar.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_CHARGE
                || avatar.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD
                || avatar.leftArmPose == HumanoidModel.ArmPose.BLOCK;

        if (!rightArmCombat) {
            model.rightArm.xRot = Mth.rotLerpRad(blend, model.rightArm.xRot, -2.45F + stroke * 0.45F);
            model.rightArm.yRot = Mth.rotLerpRad(blend, model.rightArm.yRot, -0.18F);
            model.rightArm.zRot = Mth.rotLerpRad(blend, model.rightArm.zRot, 0.12F);
        }
        if (!leftArmCombat) {
            model.leftArm.xRot = Mth.rotLerpRad(blend, model.leftArm.xRot, -2.45F - stroke * 0.45F);
            model.leftArm.yRot = Mth.rotLerpRad(blend, model.leftArm.yRot, 0.18F);
            model.leftArm.zRot = Mth.rotLerpRad(blend, model.leftArm.zRot, -0.12F);
        }
        model.rightLeg.xRot = Mth.rotLerpRad(blend, model.rightLeg.xRot, -0.50F - stroke * 0.40F);
        model.leftLeg.xRot = Mth.rotLerpRad(blend, model.leftLeg.xRot, -0.50F + stroke * 0.40F);

        float cornerTilt = com.oneshotonekill.client.movement.ClientClimbing.INSTANCE.cornerTilt(entity, partialTick);
        if (Math.abs(cornerTilt) > 0.01F) {
            float rad = (float) Math.toRadians(cornerTilt);
            model.body.zRot = Mth.rotLerpRad(blend, model.body.zRot, rad);
            model.head.zRot = Mth.rotLerpRad(blend, model.head.zRot, rad * 0.5F);
        }
    }
}
