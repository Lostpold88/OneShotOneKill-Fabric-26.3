package com.oneshotonekill.client.mixin.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.oneshotonekill.client.effect.BoogieBombClient;
import com.oneshotonekill.client.effect.BoogieDanceAnimation;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState.RenderPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unterdrückt das Zeichnen unsichtbarer Spielerfiguren vollständig.
 * <p>
 * <p>Ersetzt NeoForges {@code RenderPlayerEvent.Pre}; Fabric API hat dazu kein Gegenstück.
 * Vanilla zeichnet einen unsichtbaren Spieler nicht als Körper, wohl aber weiter dessen
 * Ausrüstung und gehaltene Gegenstände – ein Tarnmantel liefe damit als schwebender Bogen
 * durch die Arena.</p>
 * <p>
 * <p>Der Einstieg sitzt auf {@code LivingEntityRenderer#submit}, weil {@code AvatarRenderer}
 * diese Methode nicht selbst überschreibt; die Abfrage auf {@link AvatarRenderState} grenzt ihn
 * wieder auf Spielerfiguren ein.</p>
 * <p>
 * <p>Die Signatur steht ausgeschrieben, weil {@code javap} neben der eigentlichen Methode noch
 * eine Brücke {@code submit(EntityRenderState, …)} zeigt: Ohne den Deskriptor träfe der
 * Einstieg beide, und das Bild liefe zweimal durch dieselbe Prüfung.</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
   /** Richtet die Figur bereits ab dem Abschuss horizontal zum fliegenden Grappler-Haken aus. */
   @Inject(
      method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
      at = @At("RETURN"))
   private void osok$faceGrappleAnchor(LivingEntity entity, LivingEntityRenderState state,
                                       float partialTicks, CallbackInfo ci) {
      if (!(state instanceof AvatarRenderState)) {
         return;
      }
      RenderPose aim = GrapplePullState.INSTANCE.aimPose(entity, partialTicks);
      if (aim == null) {
         return;
      }

      state.bodyRot = Mth.rotLerp(aim.blend(), state.bodyRot, aim.yaw());
      // Die verbleibende vertikale Ausrichtung übernimmt HumanoidModelMixin am ausgestreckten
      // Arm. Während des eigentlichen Zugs kippt setupRotations zusätzlich die ganze Figur.
      state.yRot = Mth.rotLerp(aim.blend(), state.yRot, 0.0F);
      state.xRot = Mth.lerp(aim.blend(), state.xRot, 0.0F);
   }

   /**
    * Neigt die vollständige Spielerfigur entlang des vertikalen Zugwinkels. Bei einem Anker
    * direkt über dem Spieler sind das 90 Grad – die Figur liegt dann sichtbar in der Luft.
    */
   @Inject(
      method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
      at = @At("RETURN"))
   private void osok$tiltAlongGrapple(LivingEntityRenderState state, PoseStack poseStack,
                                      float bodyRot, float entityScale, CallbackInfo ci) {
      if (!(state instanceof AvatarRenderState avatar) || Minecraft.getInstance().level == null) {
         return;
      }
      Entity entity = Minecraft.getInstance().level.getEntity(avatar.id);
      if (!(entity instanceof LivingEntity living)) {
         return;
      }
      float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
      RenderPose pull = GrapplePullState.INSTANCE.pose(living, partialTick);
      if (pull != null) {
         // Dasselbe Vorzeichen wie Vanillas Fluglage: -90 Grad legt die Figur nach vorn.
         poseStack.rotate(Axis.XP.rotationDegrees(-pull.elevation() * pull.blend()));
         // Dynamisches Banking (Roll-Neigung) in Kurvenlage bei Querzug
         float deltaYaw = Mth.wrapDegrees(pull.yaw() - bodyRot);
         float roll = Math.clamp(deltaYaw * 0.28F, -18.0F, 18.0F) * pull.blend();
         if (Math.abs(roll) > 0.01F) {
            poseStack.rotate(Axis.ZP.rotationDegrees(roll));
         }
      }
   }

   /** Rotiert und hebt den tanzenden Spieler bei einem Salto während der Boogie-Bomb-Animation an. */
   @Inject(
      method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
      at = @At("RETURN"))
   private void osok$boogieSalto(LivingEntityRenderState state, PoseStack poseStack,
                                 float bodyRot, float entityScale, CallbackInfo ci) {
      if (!(state instanceof AvatarRenderState avatar) || Minecraft.getInstance().level == null) {
         return;
      }
      Entity entity = Minecraft.getInstance().level.getEntity(avatar.id);
      if (entity == null) {
         return;
      }
      float weight = BoogieBombClient.getWeight(entity.getUUID());
      if (weight <= 0.001F) {
         return;
      }
      float seconds = BoogieBombClient.seconds(entity.getUUID());
      float progress = BoogieDanceAnimation.saltoProgress(seconds, entity.getUUID());
      if (progress >= 0.0F && progress <= 1.0F) {
         float jumpY = BoogieDanceAnimation.saltoJumpY(progress) * weight;
         float pitch = BoogieDanceAnimation.saltoPitch(progress) * weight;

         // Drehpunkt auf Körperschwerpunkt (Hüfte Y ~ 0.95F)
         poseStack.translate(0.0F, jumpY + 0.95F, 0.0F);
         poseStack.rotate(Axis.XP.rotationDegrees(pitch));
         poseStack.translate(0.0F, -0.95F, 0.0F);
      }

      float spinProgress = BoogieDanceAnimation.spinProgress(seconds, entity.getUUID());
      if (spinProgress >= 0.0F && spinProgress <= 1.0F) {
         float spinYaw = BoogieDanceAnimation.spinYaw(spinProgress) * weight;
         poseStack.rotate(Axis.YP.rotationDegrees(spinYaw));
      }
   }

   @Inject(
      method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
      at = @At("HEAD"),
      cancellable = true)
   private void osok$hideInvisibleAvatar(LivingEntityRenderState state, PoseStack poseStack,
                                         SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
                                         CallbackInfo ci) {
      if (state instanceof AvatarRenderState && state.isInvisible) {
         ci.cancel();
      }
   }
}
