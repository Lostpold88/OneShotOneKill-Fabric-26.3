package com.oneshotonekill.shared;

import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.registry.ModDataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Erkennt feste Match-Ausrüstung (Dolch, Bogen, Pfeil) sowie Spezial-Items.
 * <p>
 * <p>Feste Ausrüstung darf weder bewegt noch gedroppt werden. Spezial-Items dürfen
 * im eigenen Spieler-Inventar frei bewegt, aber nicht weggeworfen oder in Kisten gelegt werden.</p>
 */
public final class ProtectedItems {
   private static final String LOCK_TAG = "osok_slot_locked";

   private ProtectedItems() {
   }

   /** Markiert Vanilla-Gegenstände wie Dolch, Bogen und Pfeil eindeutig als feste OSOK-Ausrüstung. */
   public static ItemStack lockToSlot(ItemStack stack) {
      stack.set(ModDataComponents.SLOT_LOCKED, Unit.INSTANCE);
      return stack;
   }

   /** Feste Ausrüstung (Schwert, Bogen, Pfeil), die an ihren Slot gebunden ist und nicht bewegt werden darf. */
   public static boolean isSlotLocked(ItemStack stack) {
      if (stack.isEmpty()) {
         return false;
      }

      if (stack.has(ModDataComponents.SLOT_LOCKED)) {
         return true;
      }

      CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && customData.copyTag().getBooleanOr(LOCK_TAG, false)) {
         return true;
      }

      // Kompatibilität mit Ausrüstung, die vor Einführung des Markers gespeichert wurde.
      return (stack.is(Items.IRON_SWORD) || stack.is(Items.BOW)) && stack.has(DataComponents.UNBREAKABLE);
   }

   /** Ob ein Item ein Spezial-Item der Mod ist (darf im Inventar bewegt, aber nicht gedroppt werden). */
   public static boolean isSpecialItem(ItemStack stack) {
      if (stack.isEmpty()) {
         return false;
      }
      for (SpecialItem specialItem : SpecialItem.values()) {
         if (stack.is(specialItem.getIcon())) {
            return true;
         }
      }
      return false;
   }

   /** Alles, was vor dem Wegwerfen / Droppen geschützt ist (feste Ausrüstung + Spezial-Items). */
   public static boolean isProtected(ItemStack stack) {
      return isSlotLocked(stack) || isSpecialItem(stack);
   }
}
