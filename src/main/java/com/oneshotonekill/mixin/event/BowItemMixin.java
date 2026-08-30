package com.oneshotonekill.mixin.event;

import com.oneshotonekill.event.InteractionGates;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sperrt Spannen und Lösen des Bogens, wenn ein System der Mod es verlangt.
 * <p>
 * <p>Fabric API hat weder zu {@code ArrowNockEvent} noch zu {@code ArrowLooseEvent} ein
 * Gegenstück. {@code UseItemCallback} deckt zwar den Rechtsklick ab und fängt damit das
 * Spannen, aber nicht das Lösen: Das läuft über {@code releaseUsing}, wenn der Spieler die
 * Taste loslässt, und ist kein Interaktionsereignis mehr. Ein Access Widener hilft nicht –
 * beide Methoden sind öffentlich, es fehlt die Abbruchmöglichkeit.</p>
 * <p>
 * <p>Der Rückgabewert entspricht dem, was NeoForge aus den beiden Ereignissen machte:
 * {@code FAIL} beim gesperrten Spannen und ein wirkungsloses Lösen ohne Pfeil. Wer mitredet,
 * steht in {@link InteractionGates}.</p>
 */
@Mixin(BowItem.class)
public abstract class BowItemMixin {
   @Inject(method = "use", at = @At("HEAD"), cancellable = true)
   private void osok$blockDraw(Level level, Player player, InteractionHand hand,
                               CallbackInfoReturnable<InteractionResult> cir) {
      if (InteractionGates.blocksBowDraw(player)) {
         cir.setReturnValue(InteractionResult.FAIL);
      }
   }

   @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
   private void osok$blockRelease(ItemStack itemStack, Level level, LivingEntity entity, int remainingTime,
                                  CallbackInfoReturnable<Boolean> cir) {
      if (entity instanceof Player player && InteractionGates.blocksBowRelease(player)) {
         cir.setReturnValue(false);
      }
   }
}
