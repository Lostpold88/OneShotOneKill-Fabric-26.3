package com.oneshotonekill.client.mixin.effect;

import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kamerafahrt der Match-Start-Sequenz: Die Kamera kreist um den Spieler, statt nur davor zu stehen.
 * <p>
 * <p>In {@code Camera#alignWithEntity} steht die Kamera in der Außenansicht bereits an der Augenposition
 * des Spielers, ausgerichtet nach Blickrichtung (bei {@code THIRD_PERSON_FRONT} schon um 180 Grad gedreht).
 * Dreht man die Kamera <em>vor</em> dem Zurückschieben um einen Winkel weiter, wandert sie auf einem Kreis
 * um den Spieler und schaut dabei weiter auf ihn. Der Einstieg liegt deshalb unmittelbar vor
 * {@code getMaxZoom}: Dessen Wandprüfung läuft entlang der Kamerarichtung und muss die neue Drehung
 * schon sehen, sonst würde die Kamera durch Wände fahren.</p>
 */
@Mixin(Camera.class)
public abstract class CameraOrbitMixin {
    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(
            method = "alignWithEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private void osok$orbitDuringCountdown(float partialTicks, CallbackInfo ci) {
        MatchStartState state = MatchStartState.INSTANCE;
        if (!state.isCountdownActive()) {
            return;
        }
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        this.setRotation(this.yRot + state.cameraOrbitYaw(partial), state.cameraPitch(partial));
    }
}
