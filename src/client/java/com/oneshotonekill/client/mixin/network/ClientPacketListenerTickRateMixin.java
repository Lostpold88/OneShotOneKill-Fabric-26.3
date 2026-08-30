package com.oneshotonekill.client.mixin.network;

import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startet den bestätigten Zeitbruch genau dann, wenn Vanilla die neue Server-Tickrate übernimmt.
 * <p>
 * <p>Der Einstieg liegt bewusst am {@code TAIL} und nicht am {@code HEAD}:
 * {@code handleTickingState} ruft als allererste Anweisung
 * {@code PacketUtils.ensureRunningOnSameThread}, das den Aufruf auf den Client-Thread umplant und
 * dann {@code RunningOnDifferentThreadException} wirft. Ein Einstieg davor liefe zuerst auf dem
 * Netzwerk-Thread und schriebe den Effektzustand aus einem fremden Thread, während der
 * Renderpfad ihn liest.</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTickRateMixin {
   @Inject(method = "handleTickingState", at = @At("TAIL"))
   private void osok$startTimeDistortion(ClientboundTickingStatePacket packet, CallbackInfo ci) {
      TimeDistortionEffects.INSTANCE.onTickRate(packet.tickRate());
   }
}
