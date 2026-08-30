package com.oneshotonekill.mixin.item;

import com.oneshotonekill.event.ItemProtectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hält geschützte Gegenstände im Inventar, statt sie werfen zu lassen.
 * <p>
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code ItemTossEvent}: Das Item-Modul
 * beschreibt Eigenschaften von Gegenständen, die Player-Events decken Angriff, Benutzen und
 * Blockabbau ab, aber keinen Wurf. Ein Access Widener genügt nicht, weil der Wurf nicht
 * gelesen, sondern verhindert werden muss.</p>
 * <p>
 * <p>{@code ServerPlayer#drop(ItemStack, boolean, boolean)} ist dabei der einzige Punkt, durch
 * den beide Wege laufen: der Wurf per Taste über {@code ServerPlayer#drop(boolean)} und der aus
 * dem geöffneten Inventar. Nur die Serverfassung wird angefasst – der Client sagt hier ohnehin
 * nichts vorher, sondern schickt ein Paket.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {
   @Inject(
      method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
      at = @At("HEAD"),
      cancellable = true)
   private void osok$keepProtectedItems(ItemStack itemStack, boolean randomly, boolean thrownFromHand,
                                        CallbackInfoReturnable<ItemEntity> cir) {
      if (ItemProtectionEvents.interceptDrop((ServerPlayer) (Object) this, itemStack)) {
         cir.setReturnValue(null);
      }
   }
}
