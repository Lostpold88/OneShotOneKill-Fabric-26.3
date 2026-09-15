package com.oneshotonekill.mixin.event;

import com.oneshotonekill.event.CombatEvents;
import com.oneshotonekill.event.ItemProtectionEvents;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oneshotonekill.movement.ClimbingNetworking;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Serverseitige Aktionssperren, die genau dort greifen, wo die Aktion ankommt.
 * <p>
 * <p>Zwei Systeme hängen hier:</p>
 * <p>
 * <ul>
 *   <li><b>Der Eiskäfig der Frost-Falle.</b> Die Eingaben sind schon auf dem Client tot
 *       ({@code MinecraftInteractionMixin}, {@code KeyboardInputMixin}) – hier steht die
 *       Auffanglinie, damit ein veränderter Client nicht doch handeln kann. Vorher lief das
 *       über fünf Fabric-Callbacks; die greifen aber erst mitten in der Vanilla-Auswertung,
 *       während ein verworfenes Paket gar nichts erst anstößt.</li>
 *   <li><b>Die Handtausch-Sperre für feste Ausrüstung.</b> Fabric API kennt kein Gegenstück zu
 *       NeoForges {@code LivingSwapItemsEvent.Hands}, und Vanilla führt den Tausch ohne Umweg
 *       im {@code switch} von {@code handlePlayerAction} aus.</li>
 * </ul>
 * <p>
 * <p><b>Warum nicht am {@code HEAD}:</b> Jeder dieser Handler beginnt mit
 * {@code PacketUtils.ensureRunningOnSameThread}, das ein auf dem Netzwerk-Thread eingetroffenes
 * Paket per Ausnahme zurückstellt. Ein Einstieg davor liefe genau einmal auf dem falschen
 * Thread – und läse damit die Frost-Liste und verschickte die Einblendung nebenläufig. Der
 * Einstieg sitzt deshalb unmittelbar hinter diesem Aufruf; laut Quelltext und {@code javap}
 * steht er in jedem der vier Handler genau einmal, ganz am Anfang.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
            cancellable = true)
    private void osok$blockPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(this.player)
                || CombatEvents.FrozenPlayerEvents.blocksAction(this.player)) {
            ci.cancel();
            return;
        }
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && ItemProtectionEvents.blocksHandSwap(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItem", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
            cancellable = true)
    private void osok$blockUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(this.player)
                || CombatEvents.FrozenPlayerEvents.blocksAction(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
            cancellable = true)
    private void osok$blockUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(this.player)
                || CombatEvents.FrozenPlayerEvents.blocksAction(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleInteract", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
            cancellable = true)
    private void osok$blockInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(this.player)
                || CombatEvents.FrozenPlayerEvents.blocksAction(this.player)) {
            ci.cancel();
        }
    }

    @SuppressWarnings("resource")
    @WrapOperation(
            method = "handlePlayerPositionChange",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V")
    )
    private void osok$wrapClimbingMovement(ServerPlayer player, MoverType type, Vec3 delta, Operation<Void> original) {
        if (ClimbingNetworking.isClimbing(player)) {
            Vec3 originalPos = player.position();
            player.move(type, new Vec3(delta.x, delta.y, 0));
            player.move(type, new Vec3(0, 0, delta.z));
            Vec3 expected = originalPos.add(delta);
            if (player.position().distanceToSqr(expected) > 0.001) {
                player.setPos(originalPos);
                player.move(type, new Vec3(0, delta.y, delta.z));
                player.move(type, new Vec3(delta.x, 0, 0));
            }
            if (player.position().distanceToSqr(expected) > 0.001 && player.level().noCollision(player, player.getBoundingBox().move(expected.subtract(player.position())))) {
                player.setPos(expected);
            }
        } else {
            original.call(player, type, delta);
        }
    }
}
