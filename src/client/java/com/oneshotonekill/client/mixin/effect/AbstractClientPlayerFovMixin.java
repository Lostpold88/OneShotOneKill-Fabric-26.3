package com.oneshotonekill.client.mixin.effect;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.oneshotonekill.client.ClientInputEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sichtfeld: Startstoß beim Match-Beginn, Vibration der laufenden Minigun, Sog im Gleitflug.
 * <p>
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code ComputeFovModifierEvent}, und ein
 * Access Widener hilft nicht – {@code getFieldOfViewModifier} ist bereits öffentlich, gebraucht
 * wird ein anderer Rückgabewert.</p>
 * <p>
 * <p>Die Abfrage auf den lokalen Spieler ist nötig, weil die Methode auf jedem
 * {@code AbstractClientPlayer} liegt; gemeint ist nur der, durch dessen Augen man sieht.</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerFovMixin {
   @ModifyReturnValue(method = "getFieldOfViewModifier", at = @At("RETURN"))
   private float osok$modifyFov(float original, boolean firstPerson, float effectScale) {
      if ((Object) this == Minecraft.getInstance().player) {
         return ClientInputEvents.modifyFovModifier(original);
      }
      return original;
   }
}
