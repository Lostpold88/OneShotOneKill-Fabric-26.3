package com.oneshotonekill.match;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.equipment.EquipmentManager;
import com.oneshotonekill.event.KillFeed.Cause;
import com.oneshotonekill.item.runtime.SlowMotionSystem;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.ThrownDevices;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.ProtectedItems;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Die 23 Stufen des Waffenspiels in vier Akten, eine je Spezial-Item plus Bogen, Dolch und Meisterdolch.
 * <p>
 * Jede Stufe sagt, was der Spieler bekommt, welche Todesursachen zählen und welche zusätzliche
 * Bedingung ein Kill erfüllen muss. Hilfs-Items töten nicht selbst: sie öffnen ein Fenster, in dem
 * ein Bogen- oder Dolch-Kill zählt. Die Zahlen entsprechen dem Plan zum Waffenspiel.
 */
@SuppressWarnings({"unused", "Convert2MethodRef"})
public enum GunGameTier {
   // Akt I: Hilfsmittel mit langem Fenster
   RADAR(1, ModItems.RADAR_PULSE, "item.oneshotonekill.radar_pulse", 2, Kit.ITEM_BASE, bow(),
      (killer, victim, facts) -> facts.victimMarked() && facts.distance() >= 25.0,
      killer -> StatusAbilities.INSTANCE.isRadarActive(killer), false, 60),
   CLOAK(1, ModItems.INVISIBILITY_CLOAK, "item.oneshotonekill.invisibility_cloak", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> StatusAbilities.INSTANCE.isVanished(killer) && facts.victimBackTurned(),
      killer -> StatusAbilities.INSTANCE.isVanished(killer), false, 300),
   MAGNET(1, ModItems.ARROW_MAGNET, "item.oneshotonekill.arrow_magnet", 2, Kit.ITEM_BASE, bow(),
      (killer, victim, facts) -> StatusAbilities.INSTANCE.remainingMagnetTicks(killer) > 0
         || KillSignals.INSTANCE.magnetWithin(killer, 100),
      killer -> StatusAbilities.INSTANCE.remainingMagnetTicks(killer) > 0
         || KillSignals.INSTANCE.magnetWithin(killer, 100), false, 300),
   SHIELD(1, ModItems.REFLECTOR_SHIELD, "item.oneshotonekill.reflector_shield", 2, Kit.ITEM_BASE, both(),
      (killer, victim, facts) -> StatusAbilities.INSTANCE.hasShield(killer)
         || KillSignals.INSTANCE.shieldBlockedWithin(killer, 300),
      killer -> StatusAbilities.INSTANCE.hasShield(killer)
         || KillSignals.INSTANCE.shieldBlockedWithin(killer, 300), false, 200),
   SMOKE(1, ModItems.SMOKE_BOMB, "item.oneshotonekill.smoke_bomb", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> ThrownDevices.INSTANCE.isInsideSmoke(killer),
      killer -> ThrownDevices.INSTANCE.isInsideSmoke(killer), false, 240),
   GLIDER(1, ModItems.GLIDER, "item.oneshotonekill.glider", 2, Kit.ITEM_BASE, bow(),
      (killer, victim, facts) -> StatusAbilities.INSTANCE.isGliding(killer) && facts.killerHeight() >= 5.0,
      killer -> StatusAbilities.INSTANCE.isGliding(killer), false, 180),
   GRAPPLE(1, ModItems.GRAPPLING_HOOK, "item.oneshotonekill.grappling_hook", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> KillSignals.INSTANCE.grapplingWithin(killer, 60),
      killer -> KillSignals.INSTANCE.grapplingWithin(killer, 60), false, 60),

   // Akt II: Direktwaffen
   EXPLOSIVE(2, ModItems.EXPLOSIVE_SHOT, "item.oneshotonekill.explosive_shot", 2, Kit.ITEM_BOW, only(Cause.EXPLOSIVE_SHOT),
      (killer, victim, facts) -> facts.distance() >= 15.0, always(), false, 60),
   CHAIN(2, ModItems.CHAIN_LIGHTNING, "item.oneshotonekill.chain_lightning", 2, Kit.ITEM_BOW, only(Cause.CHAIN_LIGHTNING),
      (killer, victim, facts) -> facts.primary(), always(), false, 60),
   RAILGUN(2, ModItems.RAILGUN, "item.oneshotonekill.railgun", 2, Kit.ITEM_ONLY, only(Cause.RAILGUN),
      (killer, victim, facts) -> facts.distance() >= 30.0, always(), false, 60),
   MINIGUN(2, ModItems.MINIGUN, "item.oneshotonekill.minigun", 2, Kit.ITEM_ONLY, only(Cause.MINIGUN),
      (killer, victim, facts) -> true, always(), false, 60),
   SENTRY(2, ModItems.SENTRY_TURRET, "item.oneshotonekill.sentry_turret", 3, Kit.ITEM_ONLY, only(Cause.SENTRY_TURRET),
      (killer, victim, facts) -> true, always(), false, 420),
   C4(2, ModItems.C4, "item.oneshotonekill.c4", 2, Kit.ITEM_ONLY, only(Cause.C4),
      (killer, victim, facts) -> true, always(), false, 60),

   // Akt III: kurze Fenster
   TELEPORT(3, ModItems.TELEPORT_GRENADE, "item.oneshotonekill.teleport_grenade", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> KillSignals.INSTANCE.teleportedWithin(killer, 100),
      killer -> KillSignals.INSTANCE.teleportedWithin(killer, 100), false, 60),
   BOOGIE(3, ModItems.BOOGIE_BOMB, "item.oneshotonekill.boogie_bomb", 2, Kit.ITEM_BASE, bow(),
      (killer, victim, facts) -> facts.victimDancing() && facts.distance() >= 12.0, always(), false, 60),
   FROST(3, ModItems.FROST_TRAP, "item.oneshotonekill.frost_trap", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> facts.victimFrozenByKiller(), always(), false, 60),
   SINGULARITY(3, ModItems.SINGULARITY, "item.oneshotonekill.singularity", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> facts.victimInKillerSingularity(), always(), false, 120),
   SLOW(3, ModItems.SLOW_MOTION, "item.oneshotonekill.slow_motion", 2, Kit.ITEM_BASE, sword(),
      (killer, victim, facts) -> SlowMotionSystem.INSTANCE.isActive(),
      killer -> SlowMotionSystem.INSTANCE.isActive(), false, 160),

   // Akt IV: Finale
   BOMBER(4, ModItems.STEALTH_BOMBER, "item.oneshotonekill.stealth_bomber", 2, Kit.ITEM_ONLY, only(Cause.STEALTH_BOMBER),
      (killer, victim, facts) -> true, always(), false, 260),
   AIRSTRIKE(4, ModItems.AIRSTRIKE, "item.oneshotonekill.airstrike", 2, Kit.ITEM_ONLY, only(Cause.AIRSTRIKE),
      (killer, victim, facts) -> true, always(), false, 200),
   BOW(4, Items.BOW, "gungame.name.bow", 3, Kit.BOW, bow(),
      (killer, victim, facts) -> facts.distance() >= 15.0, always(), false, 60),
   DAGGER(4, Items.IRON_SWORD, "gungame.name.dagger", 3, Kit.DAGGER, sword(),
      (killer, victim, facts) -> true, always(), false, 60),
   MASTER(4, Items.GOLDEN_SWORD, "gungame.name.master_dagger", 1, Kit.MASTER, sword(),
      (killer, victim, facts) -> true, always(), false, 60);

   /** Was der Spieler zu einer Stufe in die Hand bekommt. */
   public enum Kit {
      /** Item in Slot 1, Dolch in Slot 2, Bogen in der Nebenhand. */
      ITEM_BASE,
      /** Item in Slot 1, Bogen in der Nebenhand. */
      ITEM_BOW,
      /** Nur das Item. */
      ITEM_ONLY,
      /** Nur der Bogen. */
      BOW,
      /** Nur der Dolch. */
      DAGGER,
      /** Nur der Meisterdolch. */
      MASTER
   }

   /** Das Ergebnis der Prüfung eines Kills gegen die Stufe. */
   public enum Check {
      OK,
      WRONG_WEAPON,
      CONDITION
   }

   /**
    * Alles, was die Kill-Bedingung wissen muss und was sich nach dem Tod ändert.
    * Wird gemessen, bevor die Spezial-Systeme das Opfer aufräumen.
    */
   public record Facts(boolean primary, double distance, boolean victimMarked, boolean victimDancing,
                       boolean victimFrozenByKiller, boolean victimInKillerSingularity,
                       boolean victimBackTurned, double killerHeight) {
   }

   /** Die zusätzliche Bedingung einer Stufe. */
   @FunctionalInterface
   public interface Rule {
      boolean test(ServerPlayer killer, ServerPlayer victim, Facts facts);
   }

   private static Set<Cause> bow() {
      return Set.of(Cause.BOW);
   }

   private static Set<Cause> sword() {
      return Set.of(Cause.SWORD);
   }

   private static Set<Cause> both() {
      return Set.of(Cause.BOW, Cause.SWORD);
   }

   private static Set<Cause> only(Cause cause) {
      return Set.of(cause);
   }

   private static Predicate<ServerPlayer> always() {
      return player -> true;
   }

   private final int act;
   private final Item icon;
   private final String nameKey;
   private final int requiredKills;
   private final Kit kit;
   private final Set<Cause> causes;
   private final Rule rule;
   private final Predicate<ServerPlayer> window;
   private final boolean series;
   private final int resupplyTicks;

   GunGameTier(int act, Item icon, String nameKey, int requiredKills, Kit kit, Set<Cause> causes, Rule rule,
               Predicate<ServerPlayer> window, boolean series, int resupplyTicks) {
      this.act = act;
      this.icon = icon;
      this.nameKey = nameKey;
      this.requiredKills = requiredKills;
      this.kit = kit;
      this.causes = causes;
      this.rule = rule;
      this.window = window;
      this.series = series;
      this.resupplyTicks = resupplyTicks;
   }

   /** Stufe als 1-basierte Zahl. */
   public int index() {
      return ordinal() + 1;
   }

   public static int count() {
      return values().length;
   }

   public static GunGameTier byIndex(int index) {
      return values()[Math.clamp(index, 1, count()) - 1];
   }

   /** 1 bis 4. */
   public int act() {
      return act;
   }

   public String nameKey() {
      return nameKey;
   }

   /** Schlüssel des Satzes, der die Kill-Bedingung beschreibt. */
   public String conditionKey() {
      return "gungame.cond." + index();
   }

   public Component nameComponent() {
      return Component.translatable(nameKey);
   }

   public int requiredKills() {
      return requiredKills;
   }

   public boolean usesSeries() {
      return series;
   }

   public int resupplyTicks() {
      return resupplyTicks;
   }

   public Check check(ServerPlayer killer, ServerPlayer victim, Cause cause, Facts facts) {
      if (!causes.contains(cause)) {
         return Check.WRONG_WEAPON;
      }
      return rule.test(killer, victim, facts) ? Check.OK : Check.CONDITION;
   }

   /** Ist das Zeitfenster der Stufe gerade offen? Stufen ohne Fenster sind immer offen. */
   public boolean windowOpen(ServerPlayer killer) {
      return window.test(killer);
   }

   /** Der Stapel, mit dem Menü und HUD das Icon zeichnen. */
   public ItemStack iconStack() {
      return this == MASTER ? masterDagger() : new ItemStack(icon);
   }

   /** Der Meisterdolch mit eigenem 3D-Modell. Das Basisitem bleibt ein goldenes Schwert, damit Trefferprüfung und Aufräumen unverändert greifen. */
   public static ItemStack masterDagger() {
      ItemStack sword = new ItemStack(Items.GOLDEN_SWORD);
      sword.set(DataComponents.CUSTOM_NAME,
         Component.translatable("equipment.oneshotonekill.master_dagger").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
      sword.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      sword.set(DataComponents.ATTRIBUTE_MODIFIERS, EquipmentManager.createWeaponModifiers());
      sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      sword.set(DataComponents.ITEM_MODEL, OneShotOneKill.INSTANCE.id("master_dagger"));
      return sword;
   }

   /** Rüstet den Spieler mit dem Satz dieser Stufe aus. Der Aufrufer hat das Inventar schon geleert. */
   public void equip(ServerPlayer player) {
      Inventory inventory = player.getInventory();
      EquipmentManager equipment = EquipmentManager.INSTANCE;
      switch (kit) {
         case ITEM_BASE -> {
            inventory.setItem(0, new ItemStack(icon));
            inventory.setItem(1, equipment.createSword());
            player.setItemInHand(InteractionHand.OFF_HAND, equipment.createBow(player));
         }
         case ITEM_BOW -> {
            inventory.setItem(0, new ItemStack(icon));
            player.setItemInHand(InteractionHand.OFF_HAND, equipment.createBow(player));
         }
         case ITEM_ONLY -> inventory.setItem(0, new ItemStack(icon));
         case BOW -> player.setItemInHand(InteractionHand.OFF_HAND, equipment.createBow(player));
         case DAGGER -> inventory.setItem(0, equipment.createSword());
         case MASTER -> inventory.setItem(0, ProtectedItems.lockToSlot(masterDagger()));
      }
      // Gejagter: Der Träger des Meisterdolchs leuchtet für alle durch Wände.
      player.setGlowingTag(this == MASTER);
   }

   /** Hat der Spieler das Item dieser Stufe noch? Bogen und Dolch gehen nie aus. */
   public boolean hasItem(ServerPlayer player) {
      if (kit == Kit.BOW || kit == Kit.DAGGER || kit == Kit.MASTER) {
         return true;
      }
      Inventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (!stack.isEmpty() && (stack.is(icon) || (this == C4 && stack.is(ModItems.C4_CHARGE)))) {
            return true;
         }
      }
      return false;
   }

   /** Liefert nur das verbrauchte Item nach und lässt Dolch und Bogen, wie sie sind. */
   public void giveItem(ServerPlayer player) {
      if (kit == Kit.BOW || kit == Kit.DAGGER || kit == Kit.MASTER) {
         return;
      }
      Inventory inventory = player.getInventory();
      ItemStack stack = new ItemStack(icon);
      if (inventory.getItem(0).isEmpty()) {
         inventory.setItem(0, stack);
      } else {
         inventory.add(stack);
      }
   }
}
