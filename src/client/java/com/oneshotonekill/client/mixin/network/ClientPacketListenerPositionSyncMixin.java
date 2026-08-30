package com.oneshotonekill.client.mixin.network;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Verhindert das sichtbare Gleiten/Hinterherfliegen entfernter Spieler beim Sofort-Respawn.
 * <p>
 * <p>Vanilla prüft in {@code handleEntityPositionSync}, ob der Abstand zwischen alter und neuer
 * Position größer als 64 Blöcke ({@code 4096.0}) ist. Liegt ein Respawn-Punkt darunter (was in
 * Arenen der Normalfall ist), interpoliert der Client die Position über 3 Ticks (150 ms) quer
 * durch Wände und Luft.</p>
 * <p>
 * <p>Für {@link RemotePlayer} senken wir diese Schwelle auf 4 Blöcke ({@code 16.0}): Normale
 * Lauf- und Sprintbewegungen (unter 1 Block/Tick) bleiben butterweich, während Respawn- und
 * Teleportsprünge augenblicklich hart an den Zielort snappen.</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerPositionSyncMixin {
   @Unique
   private static final double MAX_PLAYER_INTERPOLATION_DIST_SQ = 16.0;

   @Shadow
   private ClientLevel level;

   @ModifyExpressionValue(
      method = "handleEntityPositionSync",
      at = @At(value = "CONSTANT", args = "doubleValue=4096.0")
   )
   private double osok$adjustPlayerInterpolationThreshold(double original, ClientboundEntityPositionSyncPacket packet) {
      if (this.level != null) {
         Entity entity = this.level.getEntity(packet.id());
         if (entity instanceof RemotePlayer) {
            return MAX_PLAYER_INTERPOLATION_DIST_SQ;
         }
      }
      return original;
   }
}
