package com.oneshotonekill.match;

import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.network.OsokPayloads.GunGameStatusPayload;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.OsokEffects;
import com.oneshotonekill.shared.ProtectedItems;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Verwaltet den Ablauf, die Progression, Ausrüstung und Nachladelogik des Waffenspiel-Modus (Gun Game).
 */
@SuppressWarnings({"CodeBlock2Expr", "ConstantValue", "resource", "unused"})
public final class GunGameManager {
   public static final GunGameManager INSTANCE = new GunGameManager();
   public static final int TOTAL_TIERS = 13;
   private static final int REPLENISH_COOLDOWN_TICKS = 60; // 3 Sekunden

   private final Map<UUID, Integer> playerTiers = new HashMap<>();
   private final Map<UUID, Integer> playerTierKills = new HashMap<>();
   private final Map<UUID, Integer> replenishCooldowns = new HashMap<>();

   private GunGameManager() {
   }

   public enum Tier {
      TIER_1(1, "OneShot Bogen", "equipment.oneshotonekill.bow", 3, ChatFormatting.YELLOW, Items.BOW,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, createBow(player));
         }),
      TIER_2(2, "OneShot Dolch", "equipment.oneshotonekill.dagger", 3, ChatFormatting.RED, Items.IRON_SWORD,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, createSword(false));
         }),
      TIER_3(3, "Explosiv-Schuss", "item.oneshotonekill.explosive_shot", 2, ChatFormatting.GOLD, ModItems.EXPLOSIVE_SHOT,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, createBow(player));
            player.getInventory().setItem(0, new ItemStack(ModItems.EXPLOSIVE_SHOT));
         }),
      TIER_4(4, "Kettenblitz", "item.oneshotonekill.chain_lightning", 2, ChatFormatting.AQUA, ModItems.CHAIN_LIGHTNING,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, createBow(player));
            player.getInventory().setItem(0, new ItemStack(ModItems.CHAIN_LIGHTNING));
         }),
      TIER_5(5, "Railgun", "item.oneshotonekill.railgun", 2, ChatFormatting.BLUE, ModItems.RAILGUN,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.RAILGUN));
         }),
      TIER_6(6, "Minigun", "item.oneshotonekill.minigun", 2, ChatFormatting.GOLD, ModItems.MINIGUN,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.MINIGUN));
         }),
      TIER_7(7, "Singularität & Bogen", "item.oneshotonekill.singularity", 2, ChatFormatting.DARK_PURPLE, ModItems.SINGULARITY,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, createBow(player));
            player.getInventory().setItem(0, new ItemStack(ModItems.SINGULARITY));
         }),
      TIER_8(8, "C4-Sprengladung", "item.oneshotonekill.c4", 1, ChatFormatting.GOLD, ModItems.C4,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.C4));
         }),
      TIER_9(9, "Frost-Falle", "item.oneshotonekill.frost_trap", 1, ChatFormatting.AQUA, ModItems.FROST_TRAP,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.FROST_TRAP));
            player.getInventory().setItem(1, createSword(false));
         }),
      TIER_10(10, "Geschützturm", "item.oneshotonekill.sentry_turret", 1, ChatFormatting.GREEN, ModItems.SENTRY_TURRET,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.SENTRY_TURRET));
         }),
      TIER_11(11, "Tarnkappenbomber", "item.oneshotonekill.stealth_bomber", 1, ChatFormatting.DARK_AQUA, ModItems.STEALTH_BOMBER,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.STEALTH_BOMBER));
         }),
      TIER_12(12, "Zeitverzerrer & Dolch", "item.oneshotonekill.slow_motion", 1, ChatFormatting.LIGHT_PURPLE, ModItems.SLOW_MOTION,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(ModItems.SLOW_MOTION));
            player.getInventory().setItem(1, createSword(false));
         }),
      TIER_13(13, "Meisterdolch", "equipment.oneshotonekill.master_dagger", 1, ChatFormatting.GOLD, Items.GOLDEN_SWORD,
         player -> {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(0, createSword(true));
         });

      private final int tierIndex;
      private final String fallbackName;
      private final String translationKey;
      private final int requiredKills;
      private final ChatFormatting color;
      private final Item icon;
      private final Consumer<ServerPlayer> equipAction;

      Tier(int tierIndex, String fallbackName, String translationKey, int requiredKills, ChatFormatting color, Item icon, Consumer<ServerPlayer> equipAction) {
         this.tierIndex = tierIndex;
         this.fallbackName = fallbackName;
         this.translationKey = translationKey;
         this.requiredKills = requiredKills;
         this.color = color;
         this.icon = icon;
         this.equipAction = equipAction;
      }

      public int getTierIndex() { return tierIndex; }
      public String getTranslationKey() { return translationKey; }
      public String getDisplayName() {
         return Component.translatable(translationKey).getString();
      }
      public int getRequiredKills() { return requiredKills; }
      public ChatFormatting getColor() { return color; }
      public Item getIcon() { return icon; }
      public void apply(ServerPlayer player) { equipAction.accept(player); }

      /**
       * Nur Abschüsse mit der Waffe der aktuellen Stufe zählen. Das verhindert insbesondere,
       * dass Mehrfachtreffer oder noch aktive Geräte nach einem Aufstieg schon die nächste
       * Stufe fortschreiben.
       */
      public boolean accepts(KillFeed.Cause cause) {
         return switch (this) {
            case TIER_1, TIER_7 -> cause == KillFeed.Cause.BOW;
            case TIER_2, TIER_9, TIER_12, TIER_13 -> cause == KillFeed.Cause.SWORD;
            case TIER_3 -> cause == KillFeed.Cause.EXPLOSIVE_SHOT;
            case TIER_4 -> cause == KillFeed.Cause.CHAIN_LIGHTNING;
            case TIER_5 -> cause == KillFeed.Cause.RAILGUN;
            case TIER_6 -> cause == KillFeed.Cause.MINIGUN;
            case TIER_8 -> cause == KillFeed.Cause.C4;
            case TIER_10 -> cause == KillFeed.Cause.SENTRY_TURRET;
            case TIER_11 -> cause == KillFeed.Cause.STEALTH_BOMBER;
         };
      }

      public static Tier byIndex(int index) {
         int bounded = Math.clamp(index, 1, TOTAL_TIERS);
         return values()[bounded - 1];
      }
   }

   public int getPlayerTier(UUID playerId) {
      return playerTiers.getOrDefault(playerId, 1);
   }

   public int getPlayerTierKills(UUID playerId) {
      return playerTierKills.getOrDefault(playerId, 0);
   }

   public Tier getTierFor(UUID playerId) {
      return Tier.byIndex(getPlayerTier(playerId));
   }

   public void startMatch(MinecraftServer server) {
      reset();
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         playerTiers.put(player.getUUID(), 1);
         playerTierKills.put(player.getUUID(), 0);
         giveTierEquipment(player);
         syncStatus(player, false);
      }
   }

   public void reset() {
      playerTiers.clear();
      playerTierKills.clear();
      replenishCooldowns.clear();
   }

   public void giveTierEquipment(ServerPlayer player) {
      // Siehe EquipmentManager: Ein neuer Waffenspiel-Spawn darf kein von einer alten
      // Endsequenz übrig gebliebenes dauerhaftes Unverwundbar-Flag behalten.
      player.setInvulnerable(false);
      player.removeAllEffects();
      player.setTicksFrozen(0);
      player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
      player.getInventory().clearContent();

      Tier tier = getTierFor(player.getUUID());
      tier.apply(player);

      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      player.experienceLevel = 0;
      player.experienceProgress = (float) getPlayerTierKills(player.getUUID()) / (float) tier.getRequiredKills();
      player.inventoryMenu.broadcastChanges();
      replenishCooldowns.remove(player.getUUID());
   }

   public void recordKill(ServerPlayer killer, ServerPlayer victim, KillFeed.Cause cause) {
      UUID killerId = killer.getUUID();
      int currentTierIndex = getPlayerTier(killerId);
      Tier tier = Tier.byIndex(currentTierIndex);
      if (MatchManager.INSTANCE.getCurrentMatchState() != MatchManager.MatchState.RUNNING
         || MatchManager.INSTANCE.getCurrentGameMode() != MatchManager.GameMode.GUN_GAME
         || !tier.accepts(cause)) {
         return;
      }
      int currentKills = getPlayerTierKills(killerId) + 1;

      if (currentKills < tier.getRequiredKills()) {
         playerTierKills.put(killerId, currentKills);
         killer.experienceProgress = (float) currentKills / (float) tier.getRequiredKills();

         float pitch = switch (currentKills) {
            case 1 -> 1.0F;
            case 2 -> 1.4F;
            default -> 1.8F;
         };
         OsokEffects.INSTANCE.sendPrivateSound(killer, SoundEvents.NOTE_BLOCK_PLING.value(), 1.2F, pitch);
         String pips = buildProgressPips(currentKills, tier.getRequiredKills());
         Feedback.actionBar(killer, "§e⚡ Stufe " + tier.getTierIndex() + " · §f" + tier.getDisplayName() + " " + pips + " §7(" + currentKills + "/" + tier.getRequiredKills() + " Kills)");
         syncStatus(killer, false);
      } else {
         if (currentTierIndex >= TOTAL_TIERS) {
            // Sieg im Gun Game!
            playerTierKills.put(killerId, tier.getRequiredKills());
            syncStatus(killer, true);
            MinecraftServer server = killer.level().getServer();
            if (server != null) {
               MatchManager.INSTANCE.endMatchWithWinner(server, "🏆 Waffenspiel-Meister (Stufe " + TOTAL_TIERS + " abgeschlossen)!");
            }
            return;
         }

         int nextTierIndex = currentTierIndex + 1;
         Tier nextTier = Tier.byIndex(nextTierIndex);
         playerTiers.put(killerId, nextTierIndex);
         playerTierKills.put(killerId, 0);

         OsokEffects.INSTANCE.sendPrivateSound(killer, SoundEvents.PLAYER_LEVELUP, 1.2F, 1.2F);
         OsokEffects.INSTANCE.sendPrivateSound(killer, SoundEvents.BEACON_POWER_SELECT, 1.0F, 1.5F);

         killer.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 15));
         killer.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("chat.oneshotonekill.gungame_tier_title", nextTier.getTierIndex()).withStyle(nextTier.getColor(), ChatFormatting.BOLD)));
         killer.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable(nextTier.getTranslationKey()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

         Feedback.actionBar(killer, Component.translatable("hud.oneshotonekill.match.level_up").getString() + " · " + Component.translatable("hud.oneshotonekill.match.new_weapon", nextTier.getDisplayName()).getString());
         giveTierEquipment(killer);

         MinecraftServer server = killer.level().getServer();
         if (server != null) {
            Component announcement = Component.literal("[OSOK] ⚡ ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
               .append(Component.translatable("chat.oneshotonekill.gungame_tier_advance",
                  killer.getScoreboardName(),
                  nextTier.getTierIndex(),
                  Component.translatable(nextTier.getTranslationKey()).getString()
               ).withStyle(ChatFormatting.GRAY));
            server.getPlayerList().broadcastSystemMessage(announcement, false);
         }

         syncStatus(killer, true);
      }
   }

   public void tick(MinecraftServer server) {
      if (server == null) return;
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         UUID uuid = player.getUUID();
         if (!player.isAlive()) {
            replenishCooldowns.remove(uuid);
            continue;
         }

         Tier tier = getTierFor(uuid);
         if (tier == Tier.TIER_1 || tier == Tier.TIER_2 || tier == Tier.TIER_13) {
            continue; // Bogen und Schwerter gehen nie aus
         }

         boolean hasWeapon = false;
         for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && (stack.is(tier.getIcon()) || stack.is(ModItems.C4_CHARGE) || stack.is(ModItems.C4))) {
               hasWeapon = true;
               break;
            }
         }

         if (!hasWeapon) {
            int count = replenishCooldowns.getOrDefault(uuid, 0) + 1;
            if (count >= REPLENISH_COOLDOWN_TICKS) {
               tier.apply(player);
               player.inventoryMenu.broadcastChanges();
               replenishCooldowns.remove(uuid);
               OsokEffects.INSTANCE.sendPrivateSound(player, SoundEvents.ITEM_PICKUP, 0.8F, 1.2F);
               Feedback.actionBar(player, Component.translatable("chat.oneshotonekill.gungame_reloaded", tier.getDisplayName()).getString());
            } else {
               replenishCooldowns.put(uuid, count);
               if (count % 20 == 0) {
                   int remainingSecs = (REPLENISH_COOLDOWN_TICKS - count) / 20;
                   Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.gungame_reloading", remainingSecs));
                }
            }
         } else {
            replenishCooldowns.remove(uuid);
         }
      }
   }

   public void syncStatus(ServerPlayer player, boolean isLevelUp) {
      Tier tier = getTierFor(player.getUUID());
      int kills = getPlayerTierKills(player.getUUID());
      ServerPlayNetworking.send(player, new GunGameStatusPayload(
         true, tier.getTierIndex(), TOTAL_TIERS, kills, tier.getRequiredKills(),
         tier.getDisplayName(), tier.getColor().name(), isLevelUp
      ));
   }

   public void clearStatus(ServerPlayer player) {
      ServerPlayNetworking.send(player, GunGameStatusPayload.inactive());
   }

   public void clearStatuses(MinecraftServer server) {
      if (server != null) {
         server.getPlayerList().getPlayers().forEach(this::clearStatus);
      }
   }

   private static String buildProgressPips(int current, int total) {
      StringBuilder sb = new StringBuilder("§8[");
      for (int i = 0; i < total; i++) {
         if (i < current) {
            sb.append("§a●");
         } else {
            sb.append("§7○");
         }
      }
      sb.append("§8]");
      return sb.toString();
   }

   private static ItemStack createSword(boolean isMaster) {
      if (!isMaster) {
         return com.oneshotonekill.equipment.EquipmentManager.INSTANCE.createSword();
      }
      ItemStack sword = new ItemStack(Items.GOLDEN_SWORD);
      sword.set(DataComponents.CUSTOM_NAME, Component.translatable("equipment.oneshotonekill.master_dagger").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
      sword.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      sword.set(DataComponents.ATTRIBUTE_MODIFIERS, com.oneshotonekill.equipment.EquipmentManager.createWeaponModifiers());
      sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      return ProtectedItems.lockToSlot(sword);
   }

   private static ItemStack createBow(ServerPlayer player) {
      return com.oneshotonekill.equipment.EquipmentManager.INSTANCE.createBow(player);
   }
}
