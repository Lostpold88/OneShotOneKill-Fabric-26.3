package com.oneshotonekill.mixin.item;

import com.oneshotonekill.item.StopUsingAware;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Meldet einem Gegenstand, dass seine Benutzung abbricht.
 * <p>
 * <p>Vanilla ruft {@code Item#releaseUsing} nur, wenn der Client das Loslassen meldet.
 * {@code stopUsingItem} kommt dagegen auch beim Waffenwechsel, und dort erfährt der Gegenstand
 * nichts mehr. Fabric API kennt kein Gegenstück zu NeoForges {@code IItemExtension#onStopUsing},
 * und ein Access Widener hilft nicht – es fehlt kein Zugriff, sondern ein Aufruf.</p>
 * <p>
 * <p>Am {@code HEAD}, damit {@code useItem} noch steht: {@code stopUsingItem} räumt es im
 * weiteren Verlauf ab. Wer gemeint ist, entscheidet {@link StopUsingAware}.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityStopUsingMixin {
   @Shadow
   protected ItemStack useItem;

   @Inject(method = "stopUsingItem", at = @At("HEAD"))
   private void osok$notifyStopUsing(CallbackInfo ci) {
      if (this.useItem.getItem() instanceof StopUsingAware aware) {
         aware.onStopUsing(this.useItem, (LivingEntity) (Object) this);
      }
   }
}
