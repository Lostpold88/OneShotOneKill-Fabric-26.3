package com.oneshotonekill.client.mixin;

import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Startet den bestätigten Zeitbruch genau dann, wenn Vanilla die neue Server-Tickrate übernimmt. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTickRateMixin {
   @Inject(method = "handleTickingState", at = @At("HEAD"))
   private void osok$startTimeDistortion(ClientboundTickingStatePacket packet, CallbackInfo ci) {
      TimeDistortionEffects.INSTANCE.onTickRate(packet.tickRate());
   }
}
