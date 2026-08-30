package com.oneshotonekill.item.types;
import com.oneshotonekill.shared.SpecialItemRules;

import com.oneshotonekill.item.runtime.ArmedShots;
import com.oneshotonekill.shared.DeviceLights;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.SlowMotionSystem;
import com.oneshotonekill.item.runtime.GrapplingHookSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Klassensammlung aller passiven und aktiven Fähigkeiten-Spezialitems.
 */
@SuppressWarnings("NullableProblems")
public final class AbilityItems {
   private AbilityItems() {}

   // --- SpecialAbilityItem.java ---
   /**
    * Basis für alle Spezial-Items, die per Rechtsklick wirken.
    * <p>
    * Die gesamte Wirkung liegt auf dem Server; der Client meldet nur Erfolg zurück, damit die
    * Handanimation läuft. Verbraucht wird das Item ausschließlich, wenn die Wirkung wirklich
    * eingetreten ist – ein Fehlversuch außerhalb der Arena kostet also nichts.
    */
   public static abstract class SpecialAbilityItem extends Item {
      protected SpecialAbilityItem(Properties properties) {
         super(properties);
      }
   
      @Override
      public InteractionResult use(Level level, Player player, InteractionHand hand) {
         if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
         }
   
         ItemStack stack = player.getItemInHand(hand);
         if (!SpecialItemRules.canUseOrExplain(serverPlayer) || !activate(serverLevel, serverPlayer, stack)) {
            return InteractionResult.FAIL;
         }
         if (consumesOnUse()) {
            stack.shrink(1);
         }
         return InteractionResult.CONSUME;
      }
   
      /** @return true, wenn die Wirkung eingetreten ist und das Item verbraucht werden darf. */
      protected abstract boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack);
   
      /** Items, die erst später verbraucht werden (etwa nach einer Zielauswahl), überschreiben das. */
      protected boolean consumesOnUse() {
         return true;
      }
   }

   // --- InvisibilityCloakItem.java ---
   /** Echter Vanish für 15 Sekunden; endet vorzeitig bei einer Eliminierung. */
   public static final class InvisibilityCloakItem extends SpecialAbilityItem {
      /** Farbe der Anzeige im Wechsel. */
      private static final int LIGHT_ON = 0xC08CFF;
      private static final int LIGHT_OFF = 0x2A1440;
   
      public InvisibilityCloakItem(Properties properties) {
         super(properties);
      }
   
   
      /** Lässt die Anzeige blinken, solange das Gerät in der Hand liegt. */
      @Override
      public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
         if (slot == EquipmentSlot.MAINHAND) {
            DeviceLights.beacon(stack, level.getGameTime(), LIGHT_ON, LIGHT_OFF);
         }
      }
   
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return StatusAbilities.INSTANCE.startVanish(player);
      }
   }

   // --- GliderItem.java ---
   /**
    * Acht Sekunden Flug mit Startschub und regelmäßigen Schubstößen.
    * <p>
    * Das Item selbst ist bewusst kein anziehbares Rüstungsteil – sonst hätte man unbegrenzten Flug
    * statt der acht Sekunden. Die Flughöhe respektiert Arena-Oberkante und Decke.
    */
   public static final class GliderItem extends SpecialAbilityItem {
      /** Bereitschaftsblau des Bedienfelds. */
      private static final int PANEL_READY = 0x4FD8FF;
      private static final int PANEL_DIM = 0x0E3A4A;
   
      public GliderItem(Properties properties) {
         super(properties);
      }
   
      /**
       * Lässt die Anzeige blinken, solange das Gerät in der Hand liegt.
       * <p>
       * Nur in der Haupthand: {@code inventoryTick} läuft für jeden Gegenstand in jedem Inventar,
       * und ein Gerät tief in der Tasche muss niemandem etwas anzeigen.
       */
      @Override
      public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
         if (slot == EquipmentSlot.MAINHAND) {
            DeviceLights.beacon(stack, level.getGameTime(), PANEL_READY, PANEL_DIM);
         }
      }
   
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return StatusAbilities.INSTANCE.startGlide(player);
      }
   }

   // --- ReflectorShieldItem.java ---
   /** Fängt den nächsten tödlichen Treffer ab – unabhängig von der Todesursache. */
   public static final class ReflectorShieldItem extends SpecialAbilityItem {
      public ReflectorShieldItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return StatusAbilities.INSTANCE.raiseShield(player);
      }
   }

   // --- ArrowMagnetItem.java ---
   /** Reflektiert gegnerische Pfeile an der sichtbaren Magnetblase für 15 Sekunden. */
   public static final class ArrowMagnetItem extends SpecialAbilityItem {
      public ArrowMagnetItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return StatusAbilities.INSTANCE.startArrowMagnet(player);
      }
   }

   // --- RadarPulseItem.java ---
   /** Lässt alle Gegner in der Arena 30 Sekunden lang aufleuchten. */
   public static final class RadarPulseItem extends SpecialAbilityItem {
      /** Farbe der Anzeige im Wechsel. */
      private static final int LIGHT_ON = 0x4FE8FF;
      private static final int LIGHT_OFF = 0x0E3A4A;
   
      public RadarPulseItem(Properties properties) {
         super(properties);
      }
   
   
      /** Lässt die Anzeige blinken, solange das Gerät in der Hand liegt. */
      @Override
      public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
         if (slot == EquipmentSlot.MAINHAND) {
            DeviceLights.beacon(stack, level.getGameTime(), LIGHT_ON, LIGHT_OFF);
         }
      }
   
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return StatusAbilities.INSTANCE.startRadarPulse(player);
      }
   }

   // --- ExplosiveShotItem.java ---
   /** Lädt den nächsten Pfeil scharf: Der Einschlag erzeugt eine Explosion. */
   public static final class ExplosiveShotItem extends SpecialAbilityItem {
      public ExplosiveShotItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return ArmedShots.INSTANCE.arm(player, ArmedShots.ShotType.EXPLOSIVE);
      }
   }

   // --- ChainLightningItem.java ---
   /** Lädt den nächsten Pfeil scharf: Der Treffer schlägt als Blitz auf bis zu zwei Gegner über. */
   public static final class ChainLightningItem extends SpecialAbilityItem {
      public ChainLightningItem(Properties properties) {
         super(properties);
      }
   
      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return ArmedShots.INSTANCE.arm(player, ArmedShots.ShotType.CHAIN_LIGHTNING);
      }
   }

   // --- SlowMotionItem.java ---
   /** Verlangsamt den gesamten Zeitfluss für exakt sieben reale Sekunden. */
   public static final class SlowMotionItem extends SpecialAbilityItem {
      public SlowMotionItem(Properties properties) {
         super(properties);
      }

      @Override
      protected boolean activate(ServerLevel level, ServerPlayer player, ItemStack stack) {
         return SlowMotionSystem.INSTANCE.activate(player);
      }
   }

   // --- GrapplingHookItem.java ---
   /**
    * Mehrfach verwendbarer Grappler: Ein Schuss verbraucht eine der zehn Druckladungen.
    * <p>
    * <p>Anders als die einmaligen Fähigkeiten schrumpft der Stapel nicht sofort. Vanillas
    * Schadenskomponente ist hier die Munitionsanzeige; dadurch bleiben Restladungen auch beim
    * Verschieben, Tod oder erneuten Einloggen ohne eigene Speicherschicht erhalten.</p>
    */
   public static final class GrapplingHookItem extends Item {
      public static final int MAX_CHARGES = 10;
      private static final int FIRE_COOLDOWN_TICKS = 6;

      public GrapplingHookItem(Properties properties) {
         super(properties.durability(MAX_CHARGES));
      }

      @Override
      public InteractionResult use(Level level, Player player, InteractionHand hand) {
         // CONSUME bestätigt die lokale Eingabe ohne den von SUCCESS ausgelösten Armschwung.
         // Die eigentliche Simulation bleibt ausschließlich auf dem Server.
         if (level.isClientSide()) {
            return InteractionResult.CONSUME;
         }
         if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.FAIL;
         }
         if (!SpecialItemRules.canUseOrExplain(serverPlayer)) {
            return InteractionResult.FAIL;
         }

         ItemStack stack = player.getItemInHand(hand);
         if (player.getCooldowns().isOnCooldown(stack)
            || !GrapplingHookSystem.INSTANCE.fire(serverLevel, serverPlayer)) {
            return InteractionResult.FAIL;
         }

         player.getCooldowns().addCooldown(stack, FIRE_COOLDOWN_TICKS);
         stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
         return InteractionResult.CONSUME;
      }

      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand,
                                                     ItemStack oldStack, ItemStack newStack) {
         // Jede Ladung ändert die Schadenskomponente. Fabric unterdrückt damit das ansonsten
         // folgende Absenken/Nachgreifen, ohne die Ladungsanzeige selbst anzutasten.
         return false;
      }
   }
}
