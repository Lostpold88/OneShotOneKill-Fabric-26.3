package com.oneshotonekill.equipment;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.ProtectedItems;

import com.oneshotonekill.OneShotOneKill;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;

/** Vergibt und entzieht die Grundausrüstung des Minigames. */
public final class EquipmentManager {
   public static final EquipmentManager INSTANCE = new EquipmentManager();

   private static final int SLOT_SWORD = 0;
   private static final int SLOT_GRAPPLER = 1;
   private static final int FULL_FOOD_LEVEL = 20;
   private static final float FULL_SATURATION = 20.0F;

   private EquipmentManager() {
   }

   public void giveOneShotEquipment(ServerPlayer player) {
      // Die Nuke macht Zuschauer dauerhaft unverwundbar. Nach Abbruch oder Reconnect kann
      // dieses Entity-Flag einen neuen Matchstart überleben; Matchausrüstung bedeutet immer,
      // dass der Spieler wieder regulär am Kampf teilnimmt.
      player.setInvulnerable(false);
      player.removeAllEffects();
      player.setTicksFrozen(0);
      removeLegacyArrows(player);
      player.getInventory().setItem(SLOT_SWORD, createSword());
      player.setItemInHand(InteractionHand.OFF_HAND, createBow(player));

      Arena activeArena = OneShotOneKill.INSTANCE.getArenas() != null
         ? OneShotOneKill.INSTANCE.getArenas().getActive() : null;
      if (activeArena == Arena.TILTED_TOWERS) {
         player.getInventory().setItem(SLOT_GRAPPLER, createInfiniteGrappler());
      } else {
         ItemStack slot1 = player.getInventory().getItem(SLOT_GRAPPLER);
         if (ProtectedItems.isSlotLocked(slot1) && slot1.is(ModItems.GRAPPLING_HOOK)) {
            player.getInventory().setItem(SLOT_GRAPPLER, ItemStack.EMPTY);
         }
      }

      restoreVitals(player);
      player.experienceLevel = 0;
      player.experienceProgress = 0.0F;
   }

   public void clearBaseEquipment(ServerPlayer player) {
      player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
      Inventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (stack.is(Items.IRON_SWORD) || stack.is(Items.BOW) || stack.is(Items.ARROW) || stack.is(Items.GOLDEN_SWORD) || ProtectedItems.isProtected(stack)) {
            inventory.setItem(slot, ItemStack.EMPTY);
         }
      }
      player.inventoryMenu.broadcastChanges();
      restoreVitals(player);
   }

   /** Entfernt den früher in Slot 9 vergebenen Infinity-Pfeil auch aus alten Spielerständen. */
   private void removeLegacyArrows(ServerPlayer player) {
      Inventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         if (inventory.getItem(slot).is(Items.ARROW)) {
            inventory.setItem(slot, ItemStack.EMPTY);
         }
      }
   }

   public static ItemAttributeModifiers createWeaponModifiers() {
      // Reines Vanilla 1.8 Verhalten:
      // 1. Instant Attack-Speed (+100.0): Jeder Klick hat 100% Wucht, kein Angriffs-Cooldown/Ladebalken
      // 2. Kein Sweeping Edge / Flächenschaden (-1.0): Präziser Einzeltreffer wie in Minecraft 1.8
      return ItemAttributeModifiers.builder()
         .add(Attributes.ATTACK_SPEED, new AttributeModifier(OneShotOneKill.INSTANCE.id("weapon_attack_speed"), 100.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
         .add(Attributes.SWEEPING_DAMAGE_RATIO, new AttributeModifier(OneShotOneKill.INSTANCE.id("weapon_no_sweep"), -1.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
         .build();
   }

   public ItemStack createSword() {
      ItemStack sword = new ItemStack(Items.IRON_SWORD);
      sword.set(DataComponents.CUSTOM_NAME, Component.literal("⚔ OneShot Dolch").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
      sword.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      sword.set(DataComponents.ATTRIBUTE_MODIFIERS, createWeaponModifiers());
      return ProtectedItems.lockToSlot(sword);
   }

   public ItemStack createBow(ServerPlayer player) {
      ItemStack bow = new ItemStack(Items.BOW);
      bow.set(DataComponents.CUSTOM_NAME, Component.literal("⚡ OneShot Bogen").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
      bow.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      player.registryAccess()
         .lookupOrThrow(Registries.ENCHANTMENT)
         .get(Enchantments.INFINITY)
         .ifPresent(infinity -> bow.enchant(infinity, 1));
      return ProtectedItems.lockToSlot(bow);
   }

   private ItemStack createInfiniteGrappler() {
      ItemStack grappler = new ItemStack(ModItems.GRAPPLING_HOOK);
      grappler.set(DataComponents.CUSTOM_NAME, Component.literal("🪝 Tilted Grappler").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
      grappler.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      return ProtectedItems.lockToSlot(grappler);
   }

   private void restoreVitals(ServerPlayer player) {
      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(FULL_FOOD_LEVEL);
      player.getFoodData().setSaturation(FULL_SATURATION);
   }
}
