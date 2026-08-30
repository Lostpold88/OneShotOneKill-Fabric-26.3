package com.oneshotonekill.mixin.item;

import com.oneshotonekill.event.ItemProtectionEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sperrt Inventarklicks, die geschützte Gegenstände bewegen würden.
 * <p>
 * <p>Zuvor hing der Schutz an einer eigenen {@code Slot}-Hülle, die {@code mayPickup} und
 * {@code mayPlace} verneinte. Die stand aber nur auf dem Server: Der Client sagte den Zug
 * voraus, zeigte den Dolch am Mauszeiger und bekam ihn im nächsten Inventarpaket
 * zurückgenommen – das sichtbare Zurückspringen.</p>
 * <p>
 * <p>{@code AbstractContainerMenu#clicked} ist der eine Punkt, durch den jeder Klickweg läuft:
 * Aufnehmen, Umschalt-Klick, Hotbar-Tausch, Wegwerfen, Mittelklick und das Ziehen über mehrere
 * Slots. Vanilla ruft ihn auf beiden Seiten auf – aus {@code MultiPlayerGameMode} und aus
 * {@code ServerGamePacketListenerImpl}. Ein Abbruch hier heißt deshalb: keine Vorhersage, keine
 * Korrektur, kein Zurückspringen. Fabric API bietet für den Inventarklick nichts an, und ein
 * Access Widener hilft nicht – es fehlt kein Zugriff, sondern die Abbruchmöglichkeit.</p>
 * <p>
 * <p>Der Einstieg sitzt an {@code clicked} und nicht am privaten {@code doClick}: {@code clicked}
 * ist die öffentliche Fassung, die beide Aufrufer benutzen, und sie fängt zusätzlich die
 * Ausnahmebehandlung ab.</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuClickMixin {
   @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
   private void osok$blockProtectedClick(int slotIndex, int buttonNum, ContainerInput containerInput,
                                         Player player, CallbackInfo ci) {
      if (ItemProtectionEvents.blocksContainerClick(
         (AbstractContainerMenu) (Object) this, slotIndex, buttonNum, containerInput, player)) {
         ci.cancel();
      }
   }
}
