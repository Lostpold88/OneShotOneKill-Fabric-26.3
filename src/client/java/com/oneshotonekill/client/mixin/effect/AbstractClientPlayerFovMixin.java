package com.oneshotonekill.client.mixin.effect;

import com.oneshotonekill.client.ClientInputEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sichtfeld: Startstoß beim Match-Beginn, Vibration der laufenden Minigun, Sog im Gleitflug.
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code ComputeFovModifierEvent}, und ein
 * Access Widener hilft nicht – {@code getFieldOfViewModifier} ist bereits öffentlich, gebraucht
 * wird ein anderer Rückgabewert.</p>
 *
 * <p>Die Abfrage auf den lokalen Spieler ist nötig, weil die Methode auf jedem
 * {@code AbstractClientPlayer} liegt; gemeint ist nur der, durch dessen Augen man sieht.</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerFovMixin {
   @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
   private void osok$modifyFov(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
      if ((Object) this == Minecraft.getInstance().player) {
         cir.setReturnValue(ClientInputEvents.modifyFovModifier(cir.getReturnValueF()));
      }
   }
}
