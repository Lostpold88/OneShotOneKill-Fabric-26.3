package com.oneshotonekill.mixin.match;

import com.oneshotonekill.match.ScoreboardManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Setzt den Namen in der Tabellenliste auf die Zeile aus {@link ScoreboardManager}.
 * <p>
 * <p>Fabric API bietet dafür weder Ereignis noch Registry: Weder das Lifecycle- noch das
 * Entity-Event-Modul kennt einen Rückruf zur Anzeigezeile eines Spielers, und die
 * Netzwerkmodule setzen nur Pakete zusammen, statt ihren Inhalt zu bestimmen. Ein Access
 * Widener hilft ebenfalls nicht, denn {@code ServerPlayer#getTabListDisplayName} ist bereits
 * öffentlich – es fehlt kein Zugriff, sondern ein anderer Rückgabewert.</p>
 * <p>
 * <p>Vanilla gibt hier {@code null} zurück, worauf {@code ClientboundPlayerInfoUpdatePacket}
 * auf den Profilnamen zurückfällt. Genau diesen Rückfall ersetzt der Mixin.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabListMixin {
   @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
   private void osok$tabListDisplayName(CallbackInfoReturnable<Component> cir) {
      cir.setReturnValue(ScoreboardManager.INSTANCE.getTabDisplayName((ServerPlayer) (Object) this));
   }
}
