package com.oneshotonekill.item.types;
import com.oneshotonekill.shared.SpecialItemRules;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.shared.DeviceLights;
import com.oneshotonekill.item.runtime.StealthBomberSystem;
import com.oneshotonekill.item.runtime.ThrownDevices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Klassensammlung aller platzierbaren und geworfenen Spezial-Items.
 */
public final class DeployableItems {
   private DeployableItems() {}

   // --- PlacedSpecialItem.java ---
   /**
    * Basis für Spezial-Items, die auf einen Block gesetzt werden (Frost-Falle, C4, Geschützturm).
    *
    * Gesetzt wird immer auf die Fläche, die angeklickt wurde – die Ladung liegt also dort, wo der
    * Spieler hinzeigt, und nie im Block selbst.
    */
   public static abstract class PlacedSpecialItem extends Item {
      protected PlacedSpecialItem(Properties properties) {
         super(properties);
      }
   
      @Override
      public InteractionResult useOn(UseOnContext context) {
         if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
         }
         if (!SpecialItemRules.canUseOrExplain(player)) {
            return InteractionResult.FAIL;
         }
   
         BlockPos target = context.getClickedPos().relative(context.getClickedFace());
         if (!level.getBlockState(target).isAir() || context.getClickedFace() == Direction.DOWN) {
            return InteractionResult.FAIL;
         }
         if (!place(level, player, target)) {
            return InteractionResult.FAIL;
         }
   
         context.getItemInHand().shrink(1);
         return InteractionResult.CONSUME;
      }
   
      /** @return true, wenn tatsächlich etwas platziert wurde. */
      protected abstract boolean place(ServerLevel level, ServerPlayer player, BlockPos pos);
   }

   // --- SentryTurretItem.java ---
   /**
    * Stellt einen Geschützturm auf, der 20 Sekunden lang selbstständig feuert.
    *
    * Als einzige Waffe im Spiel tötet er nicht mit einem Treffer: Er zielt automatisch und ohne
    * Fehler, mit Sofort-Kill wäre jede von ihm eingesehene Deckung unbetretbar.
    */
   public static final class SentryTurretItem extends PlacedSpecialItem {
      public SentryTurretItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean place(ServerLevel level, ServerPlayer player, BlockPos pos) {
         return Deployables.INSTANCE.placeSentryTurret(level, player, pos);
      }
   }

   // --- FrostTrapItem.java ---
   /**
    * Legt eine Frostplatte aus, die den ersten Spieler sieben Sekunden festfriert – den Besitzer
    * eingeschlossen, sobald er die Platte verlassen hat und wieder hineintritt.
    *
    * Die Platte bleibt liegen, bis jemand hineintritt – sie verfällt nicht von selbst und wird nur
    * bei Match-Ende, Map-Wechsel und Serverstopp eingesammelt.
    */
   public static final class FrostTrapItem extends PlacedSpecialItem {
      public FrostTrapItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean place(ServerLevel level, ServerPlayer player, BlockPos pos) {
         return Deployables.INSTANCE.placeFrostTrap(level, player, pos);
      }
   }

   // --- SmokeBombItem.java ---
   /**
    * Wirft eine Rauchgranate: dichte, blendende Wand da, wo sie liegen bleibt.
    *
    * Sie versetzt niemanden mehr. Das machte aus ihr eine Fluchttaste, bei der der Nebel nur
    * Beiwerk war; jetzt ist der Nebel das ganze Gerät.
    */
   public static final class SmokeBombItem extends AbilityItems.SpecialAbilityItem {
      public SmokeBombItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return ThrownDevices.INSTANCE.throwDevice(level, player, ThrownDevices.DeviceType.SMOKE);
      }
   }

   // --- SingularityItem.java ---
   /**
    * Reißt fünf Sekunden lang alle Gegner im Umkreis von 10 Blöcken zum Zentrum.
    *
    * Richtet selbst keinen Schaden an – die Singularität ist ein Aufbau-Item für Luftangriff,
    * C4 und Tarnkappenbomber. Der Werfer bleibt vom Sog ausgenommen.
    */
   public static final class SingularityItem extends AbilityItems.SpecialAbilityItem {
      public SingularityItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return ThrownDevices.INSTANCE.throwDevice(level, player, ThrownDevices.DeviceType.SINGULARITY);
      }
   }

   // --- TeleportGrenadeItem.java ---
   /**
    * Versetzt den Werfer an den Einschlag und stößt alle Gegner im Umkreis von 5 Blöcken weg.
    *
    * Der Ankunftspunkt wird geprüft: liegt die Granate in einer Nische oder halb im Boden,
    * unterbleibt der Sprung, statt den Werfer in einer Wand festzusetzen.
    */
   public static final class TeleportGrenadeItem extends AbilityItems.SpecialAbilityItem {
      public TeleportGrenadeItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return ThrownDevices.INSTANCE.throwDevice(level, player, ThrownDevices.DeviceType.TELEPORT);
      }
   }

   // --- AirstrikeItem.java ---
   /**
    * Das Item selbst löst nichts aus – es meldet nur eine erfolgreiche Interaktion,
    * damit {@code AirstrikeUseMixin} auf dem Client das Radar öffnen kann.
    */
   public static final class AirstrikeItem extends Item {
      /** Bereitschaftsgrün des Schirms und das dunkle Feld dazwischen. */
      private static final int SCREEN_READY = 0x3CFF88;
      private static final int SCREEN_DIM = 0x0E4A2A;
   
      public AirstrikeItem(Properties properties) {
         super(properties);
      }
   
      /**
       * Lässt die Anzeige blinken, solange das Gerät in der Hand liegt.
       *
       * Nur in der Haupthand: {@code inventoryTick} läuft für jeden Gegenstand in jedem Inventar,
       * und ein Gerät tief in der Tasche muss niemandem etwas anzeigen.
       */
      @Override
      public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
         if (slot == EquipmentSlot.MAINHAND) {
            DeviceLights.beacon(stack, level.getGameTime(), SCREEN_READY, SCREEN_DIM);
         }
      }
   
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      @Override
      public InteractionResult use(Level level, Player player, InteractionHand hand) {
         return InteractionResult.SUCCESS;
      }
   }

   // --- StealthBomberItem.java ---
   /**
    * Setzt einen bombenwerfenden Tarnkappenbomber auf einen Gegner an.
    *
    * Der Rechtsklick fordert nur die Zielliste beim Server an; geöffnet wird das Menü, sobald sie
    * eintrifft. Verbraucht wird das Item erst mit der Wahl – wer das Menü wieder schließt, behält es.
    */
   public static final class StealthBomberItem extends AbilityItems.SpecialAbilityItem {
      public StealthBomberItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return StealthBomberSystem.INSTANCE.openTargetMenu(player);
      }
   
      @Override
      protected boolean consumesOnUse() {
         return false;
      }
   }
}
