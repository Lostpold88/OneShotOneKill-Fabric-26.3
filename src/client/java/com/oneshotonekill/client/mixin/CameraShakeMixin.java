package com.oneshotonekill.client.mixin;

import com.oneshotonekill.client.state.ClientStates.CameraShakeState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lässt die Kamera bei nahen Explosionen wackeln.
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code ViewportEvent.ComputeCameraAngles}.
 * Ein Access Widener genügt nicht: {@code Camera#setRotation} nimmt nur Gier und Nick entgegen
 * und setzt die Rollachse fest auf null – für das Wackeln fehlt also nicht der Zugriff, sondern
 * ein dritter Winkel.</p>
 *
 * <p>Deshalb wird die Drehung nach dem Vanilla-Aufruf noch einmal aufgebaut, diesmal mit allen
 * drei Winkeln, und die abgeleiteten Achsen werden genauso nachgezogen wie in
 * {@code setRotation} selbst. {@code xRot} und {@code yRot} bleiben unangetastet: Die
 * Spiegelkamera der Egoansicht rechnet mit ihnen weiter, und ein dort schon eingerechnetes
 * Wackeln käme doppelt an.</p>
 */
@Mixin(Camera.class)
public abstract class CameraShakeMixin {
   @Shadow
   @Final
   private static Vector3fc FORWARDS;

   @Shadow
   @Final
   private static Vector3fc UP;

   @Shadow
   @Final
   private static Vector3fc LEFT;

   @Shadow
   @Final
   private Quaternionf rotation;

   @Shadow
   @Final
   private Vector3f forwards;

   @Shadow
   @Final
   private Vector3f up;

   @Shadow
   @Final
   private Vector3f left;

   @Shadow
   private int matrixPropertiesDirty;

   @Inject(method = "setRotation", at = @At("RETURN"))
   private void osok$applyShake(float yRot, float xRot, CallbackInfo ci) {
      CameraShakeState shake = CameraShakeState.INSTANCE;
      if (!shake.isShaking()) {
         return;
      }

      float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
      float yaw = yRot + shake.getYawOffset(partialTick);
      float pitch = xRot + shake.getPitchOffset(partialTick);
      float roll = shake.getRollOffset(partialTick);

      this.rotation.rotationYXZ(
         (float) Math.PI - yaw * (float) (Math.PI / 180.0),
         -pitch * (float) (Math.PI / 180.0),
         roll * (float) (Math.PI / 180.0));
      FORWARDS.rotate(this.rotation, this.forwards);
      UP.rotate(this.rotation, this.up);
      LEFT.rotate(this.rotation, this.left);
      this.matrixPropertiesDirty |= 3;
   }
}
