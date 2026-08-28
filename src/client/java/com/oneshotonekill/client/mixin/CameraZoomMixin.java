package com.oneshotonekill.client.mixin;

import com.oneshotonekill.client.ClientInputEvents;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Rückt die Kamera im Countdown vor das Gesicht.
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code CalculateDetachedCameraDistanceEvent}.
 * Geändert wird nur der gewünschte Abstand, den {@code Camera#alignWithEntity} an
 * {@code getMaxZoom} übergibt – die Verdeckungsprüfung gegen Wände macht Vanilla darin
 * anschließend selbst.</p>
 *
 * <p>{@code javap -c} auf {@code Camera} zeigt in {@code alignWithEntity} genau einen
 * {@code invokevirtual getMaxZoom:(F)F}; der Einstieg braucht deshalb weder {@code ordinal}
 * noch {@code slice}.</p>
 */
@Mixin(Camera.class)
public abstract class CameraZoomMixin {
   @ModifyArg(
      method = "alignWithEntity",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
   private float osok$countdownDistance(float cameraDistance) {
      return ClientInputEvents.modifyDetachedCameraDistance(cameraDistance);
   }
}
