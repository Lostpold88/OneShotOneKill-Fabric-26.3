package com.oneshotonekill.mixin.item;

import com.oneshotonekill.event.ItemProtectionEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Liefert dem OneShot-Bogen virtuelle Munition.
 * <p>
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code LivingGetProjectileEvent}. Der
 * Bogen soll ohne Pfeil im Inventar schießen, und das lässt sich weder über ein Ereignis noch
 * über einen Access Widener erreichen: {@code Player#getProjectile} ist bereits öffentlich,
 * gebraucht wird ein anderer Rückgabewert.</p>
 * <p>
 * <p>Der Mixin sitzt auf {@code Player} und wirkt damit auf beiden Seiten. Das ist Absicht:
 * {@code BowItem#use} fragt clientseitig dasselbe ab, und eine nur serverseitige Antwort
 * ließe den Bogen auf dem Client stumm bleiben.</p>
 */
@Mixin(Player.class)
public abstract class PlayerProjectileMixin {
   @Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
   private void osok$virtualArrow(ItemStack heldWeapon, CallbackInfoReturnable<ItemStack> cir) {
      ItemStack virtual = ItemProtectionEvents.virtualProjectile(heldWeapon);
      if (virtual != null) {
         cir.setReturnValue(virtual);
      }
   }
}
