package com.oneshotonekill.item;

import com.oneshotonekill.registry.ModItems;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Die Spezial-Items, die gewichtet in der Arena erscheinen oder als Killstreak-Belohnung fallen.
 *
 * Die Reihenfolge bestimmt die Anzeige im Gewichtungsmenü und den Index in der Netzwerkübertragung –
 * neue Items werden deshalb hinten angehängt, nicht dazwischengeschoben.
 */
public enum SpecialItem {
   RADAR_PULSE("radar_pulse", "Radar-Puls", ModItems.RADAR_PULSE),
   EXPLOSIVE_SHOT("explosive_shot", "Explosiv-Schuss", ModItems.EXPLOSIVE_SHOT),
   REFLECTOR_SHIELD("reflector_shield", "Reflektor-Schild", ModItems.REFLECTOR_SHIELD),
   SMOKE_BOMB("smoke_bomb", "Rauchbombe", ModItems.SMOKE_BOMB),
   FROST_TRAP("frost_trap", "Frost-Falle", ModItems.FROST_TRAP),
   MINIGUN("minigun", "Minigun", ModItems.MINIGUN),
   TELEPORT_GRENADE("teleport_grenade", "Teleport-Granate", ModItems.TELEPORT_GRENADE),
   INVISIBILITY_CLOAK("invisibility_cloak", "Unsichtbarkeits-Mantel", ModItems.INVISIBILITY_CLOAK),
   ARROW_MAGNET("arrow_magnet", "Pfeil-Magnetfeld", ModItems.ARROW_MAGNET),
   CHAIN_LIGHTNING("chain_lightning", "Kettenblitz-Schuss", ModItems.CHAIN_LIGHTNING),
   STEALTH_BOMBER("stealth_bomber", "Tarnkappenbomber", ModItems.STEALTH_BOMBER),
   AIRSTRIKE("airstrike", "Luftangriff", ModItems.AIRSTRIKE),
   C4("c4", "C4-Ladung", ModItems.C4),
   RAILGUN("railgun", "Railgun", ModItems.RAILGUN),
   SINGULARITY("singularity", "Singularität", ModItems.SINGULARITY),
   GLIDER("glider", "Gleitflug", ModItems.GLIDER),
   SENTRY_TURRET("sentry_turret", "Geschützturm", ModItems.SENTRY_TURRET),
   SLOW_MOTION("slow_motion", "Zeitverzerrer", ModItems.SLOW_MOTION),
   GRAPPLING_HOOK("grappling_hook", "Grappling Hook", ModItems.GRAPPLING_HOOK);

   private static final Map<String, SpecialItem> BY_ID = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(SpecialItem::getId, Function.identity()));

   private final String id;
   private final String displayName;
   private final Item icon;

   SpecialItem(String id, String displayName, Item icon) {
      this.id = id;
      this.displayName = displayName;
      this.icon = icon;
   }

   public String getId() {
      return id;
   }

   public String getDisplayName() {
      return displayName;
   }

   public Item getIcon() {
      return icon;
   }

   public ItemStack createStack() {
      return new ItemStack(icon);
   }

   public static SpecialItem fromId(String id) {
      return BY_ID.get(id);
   }

   public static SpecialItem fromItem(Item item) {
      if (item == null) {
         return null;
      }
      for (SpecialItem specialItem : values()) {
         if (item.equals(specialItem.getIcon())) {
            return specialItem;
         }
      }
      return null;
   }

   public static SpecialItem fromStack(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return null;
      }
      for (SpecialItem specialItem : values()) {
         if (stack.is(specialItem.getIcon())) {
            return specialItem;
         }
      }
      return null;
   }

   public enum Mode {
      STREAK,
      SPAWN,
      BOTH;

      public boolean getAllowsGroundSpawns() {
         return this == SPAWN || this == BOTH;
      }

      public boolean getAllowsStreakRewards() {
         return this == STREAK || this == BOTH;
      }
   }

}
