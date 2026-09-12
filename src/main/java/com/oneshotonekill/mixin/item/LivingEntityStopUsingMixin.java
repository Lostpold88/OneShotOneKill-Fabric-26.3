package com.oneshotonekill.mixin.item;

import com.oneshotonekill.item.runtime.RailgunSystem;
import com.oneshotonekill.registry.ModItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bricht Ladevorgänge sauber ab, wenn die Benutzung eines Gegenstands unterbrochen wird (z. B. Waffenwechsel).
 * <p>
 * Vanilla ruft {@code Item#releaseUsing} nur, wenn der Client das Loslassen meldet.
 * {@code stopUsingItem} kommt dagegen auch beim Waffenwechsel.
 * Am {@code HEAD}, damit {@code useItem} noch steht: {@code stopUsingItem} räumt es im weiteren Verlauf ab.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityStopUsingMixin {
   @Shadow
   protected ItemStack useItem;

   @Inject(method = "stopUsingItem", at = @At("HEAD"))
   private void osok$notifyStopUsing(CallbackInfo ci) {
      if (this.useItem.is(ModItems.RAILGUN)) {
         RailgunSystem.INSTANCE.cancelCharge(this.useItem);
      }
   }
}
