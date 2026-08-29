package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.shared.ProtectedItems;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hält die Wurftaste schon auf dem Client an, wenn der ausgewählte Slot geschützt ist.
 *
 * <p>{@code ServerPlayerDropMixin} verhindert den Wurf serverseitig und legt den Gegenstand
 * zurück – für sich allein aber zu spät für das Auge: {@code LocalPlayer#drop} nimmt den Stapel
 * vorher aus dem Inventar und schickt erst dann das Paket. Der Gegenstand verschwand also kurz
 * aus der Hotbar und kam mit dem nächsten Inventarpaket zurück.</p>
 *
 * <p>Derselbe Fehler wie beim Inventarklick, dieselbe Antwort: die Aktion dort abbrechen, wo sie
 * ausgelöst wird. Die serverseitige Prüfung bleibt als Auffanglinie bestehen.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDropMixin {
   @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
   private void osok$keepProtectedItems(boolean all, CallbackInfoReturnable<Boolean> cir) {
      LocalPlayer self = (LocalPlayer) (Object) this;
      if (ProtectedItems.isProtected(self.getInventory().getSelectedItem())) {
         cir.setReturnValue(false);
      }
   }
}
