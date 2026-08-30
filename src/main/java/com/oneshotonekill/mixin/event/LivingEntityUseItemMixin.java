package com.oneshotonekill.mixin.event;

import com.oneshotonekill.event.InteractionGates;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Verhindert den Beginn einer Item-Nutzung, wenn ein System der Mod sie sperrt.
 * <p>
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code LivingEntityUseItemEvent.Start}.
 * {@code UseItemCallback} greift nur beim Rechtsklick und lässt alles durch, was die Nutzung
 * ohne diesen Weg startet – etwa ein Bogen, der über {@code BowItem#use} weiterreicht. Ein
 * Access Widener ändert nichts, weil {@code startUsingItem} bereits öffentlich ist: Es fehlt
 * kein Zugriff, sondern die Abbruchmöglichkeit.</p>
 * <p>
 * <p>Wer alles mitredet, steht in {@link InteractionGates}.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityUseItemMixin {
   @Inject(method = "startUsingItem", at = @At("HEAD"), cancellable = true)
   private void osok$blockUseStart(InteractionHand hand, CallbackInfo ci) {
      LivingEntity self = (LivingEntity) (Object) this;
      if (InteractionGates.blocksItemUseStart(self, self.getItemInHand(hand))) {
         ci.cancel();
      }
   }
}
