package com.oneshotonekill.event;

import com.oneshotonekill.nuke.NukeSequenceManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Die drei Interaktionswege, für die Fabric API kein Ereignis anbietet.
 *
 * <p>Zwei Systeme der Mod müssen den Beginn einer Item-Nutzung sowie das Spannen und Lösen des
 * Bogens abfangen: das Abnehmen einer Haftladung und die Nuke-Sequenz. Der Eiskäfig der
 * Frost-Falle stand hier ebenfalls, wird inzwischen aber schon eine Ebene früher abgefangen –
 * auf dem Client von {@code MinecraftInteractionMixin}, auf dem Server von
 * {@code ServerGamePacketListenerMixin}. Unter NeoForge hing jedes davon an
 * {@code LivingEntityUseItemEvent.Start},
 * {@code ArrowNockEvent} und {@code ArrowLooseEvent}. Fabric API hat zu keinem der drei ein
 * Gegenstück, und ein Access Widener hilft nicht: Es fehlt kein Zugriff, sondern eine
 * Abbruchmöglichkeit mitten in einer Vanilla-Methode.</p>
 *
 * <p>Statt drei Mixins je System gibt es zwei ({@code LivingEntityUseItemMixin} und
 * {@code BowItemMixin}), die hierher fragen. Diese Klasse ist die einzige Stelle, an der steht,
 * wer alles mitreden darf.</p>
 */
public final class InteractionGates {
   private InteractionGates() {
   }

   /**
    * @return {@code true}, wenn {@code LivingEntity#startUsingItem} nicht ausgeführt werden darf
    */
   public static boolean blocksItemUseStart(LivingEntity entity, ItemStack stack) {
      return ItemProtectionEvents.ChargeInteractionEvents.blocksItemUseStart(entity, stack);
   }

   /**
    * @return {@code true}, wenn der Bogen nicht gespannt werden darf
    */
   public static boolean blocksBowDraw(Player player) {
      return NukeSequenceManager.LockEvents.blocksBow()
         || ItemProtectionEvents.ChargeInteractionEvents.blocksBowDraw(player);
   }

   /**
    * @return {@code true}, wenn kein Pfeil abgehen darf
    */
   public static boolean blocksBowRelease(Player player) {
      return NukeSequenceManager.LockEvents.blocksBow()
         || ItemProtectionEvents.ChargeInteractionEvents.blocksBowRelease(player);
   }
}
