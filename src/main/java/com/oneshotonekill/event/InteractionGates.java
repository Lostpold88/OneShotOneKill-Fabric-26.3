package com.oneshotonekill.event;

import com.oneshotonekill.item.runtime.GrapplingHookSystem;
import com.oneshotonekill.nuke.NukeSequenceManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * Die drei Interaktionswege, für die Fabric API kein Ereignis anbietet.
 * <p>
 * <p>Drei Systeme der Mod müssen den Beginn einer Item-Nutzung sowie das Spannen und Lösen des
 * Bogens abfangen: das Abnehmen einer Haftladung, die Nuke-Sequenz und der Zug des Grappling Hooks.
 * Der Eiskäfig der Frost-Falle stand hier ebenfalls, wird inzwischen aber schon eine Ebene früher abgefangen –
 * auf dem Client von {@code MinecraftInteractionMixin}, auf dem Server von
 * {@code ServerGamePacketListenerMixin}. Unter NeoForge hing jedes davon an
 * {@code LivingEntityUseItemEvent.Start},
 * {@code ArrowNockEvent} und {@code ArrowLooseEvent}. Fabric API hat zu keinem der drei ein
 * Gegenstück, und ein Access Widener hilft nicht: Es fehlt kein Zugriff, sondern eine
 * Abbruchmöglichkeit mitten in einer Vanilla-Methode.</p>
 * <p>
 * <p>Statt drei Mixins je System gibt es zwei ({@code LivingEntityUseItemMixin} und
 * {@code BowItemMixin}), die hierher fragen. Diese Klasse ist die einzige Stelle, an der steht,
 * wer alles mitreden darf.</p>
 */
public final class InteractionGates {
    private static volatile Predicate<Player> clientPullGate;

    private InteractionGates() {
    }

    public static void registerClientPullGate(Predicate<Player> gate) {
        clientPullGate = gate;
    }

    @SuppressWarnings("resource")
    public static boolean isGrapplePulling(Player player) {
        if (player == null) {
            return false;
        }
        if (player.level().isClientSide()) {
            return clientPullGate != null && clientPullGate.test(player);
        }
        return GrapplingHookSystem.INSTANCE.isPulling(player);
    }

    /**
     * @return {@code true}, wenn {@code LivingEntity#startUsingItem} nicht ausgeführt werden darf
     */
    public static boolean blocksItemUseStart(LivingEntity entity, ItemStack stack) {
        if (entity instanceof Player player && (isDancing(player) || (stack.is(Items.BOW) && isGrapplePulling(player)))) {
            player.stopUsingItem();
            return true;
        }
        return ItemProtectionEvents.ChargeInteractionEvents.blocksItemUseStart(entity, stack);
    }

    /**
     * @return {@code true}, wenn der Bogen nicht gespannt werden darf
     */
    public static boolean blocksBowDraw(Player player) {
        if (isDancing(player) || isGrapplePulling(player)) {
            player.stopUsingItem();
            return true;
        }
        return NukeSequenceManager.LockEvents.blocksBow()
                || ItemProtectionEvents.ChargeInteractionEvents.blocksBowDraw(player);
    }

    /**
     * @return {@code true}, wenn kein Pfeil abgehen darf
     */
    public static boolean blocksBowRelease(Player player) {
        if (isDancing(player) || isGrapplePulling(player)) {
            player.stopUsingItem();
            return true;
        }
        return NukeSequenceManager.LockEvents.blocksBow()
                || ItemProtectionEvents.ChargeInteractionEvents.blocksBowRelease(player);
    }

    private static volatile Predicate<Player> clientDanceGate;

    public static void registerClientDanceGate(Predicate<Player> gate) {
        clientDanceGate = gate;
    }

    public static boolean isDancing(Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
            return com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(serverPlayer);
        return player != null && clientDanceGate != null && clientDanceGate.test(player);
    }
}
