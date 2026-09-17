package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.shared.ProtectedItems;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hält die Wurftaste schon auf dem Client an, wenn der ausgewählte Slot geschützt ist.
 * <p>
 * {@code ServerPlayerDropMixin} verhindert den Wurf serverseitig und legt den Gegenstand
 * zurück – für sich allein aber zu spät für das Auge: {@code MultiPlayerGameMode#dropItem} nimmt den Stapel
 * vorher aus dem Inventar und schickt erst dann das Paket. Der Gegenstand verschwand also kurz
 * aus der Hotbar und kam mit dem nächsten Inventarpaket zurück.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeDropMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void osok$keepProtectedItems(LocalPlayer player, boolean all, CallbackInfo ci) {
        if (ProtectedItems.isProtected(player.getInventory().getSelectedItem())) {
            ci.cancel();
        }
    }
}
