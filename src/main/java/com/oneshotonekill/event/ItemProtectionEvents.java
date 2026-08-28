package com.oneshotonekill.event;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.shared.ProtectedItems;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.registry.ModItems;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import org.jspecify.annotations.Nullable;

public final class ItemProtectionEvents {
   /** Feste Ausrüstung – Dolch, Bogen, Pfeil – ist an ihren Slot gebunden. */
   private static final String LOCK_MESSAGE = "§c✖ Feste Ausrüstung bleibt in ihrem Slot";
   private static final String DROP_MESSAGE = "§c✖ Items können nicht weggeworfen werden";
   private static final String STASH_MESSAGE = "§c✖ Spezial-Items bleiben im eigenen Inventar";

   private ItemProtectionEvents() {
   }

   /*
    * Die Schutzwege haben in Fabric API kein Ereignis und laufen deshalb alle über Mixins: der
    * Inventarklick, das Wegwerfen, der Handtausch und die virtuelle Munition des OneShot-Bogens.
    * Die Begründung steht jeweils in der Mixin-Klasse. Über Fabric-Callbacks angemeldet werden
    * nur die beiden Klickwege der Haftladung, siehe ChargeInteractionEvents#register().
    */

   /**
    * Ersetzt NeoForges {@code ItemTossEvent}.
    *
    * Aufgerufen aus {@code ServerPlayerDropMixin}, bevor die Wurf-Entity entsteht. Der beim
    * Q-Drop geleerte ausgewählte Slot ist zu diesem Zeitpunkt bereits leer und wird deshalb
    * ausdrücklich wieder befüllt.
    *
    * @return {@code true}, wenn der Wurf nicht stattfinden darf
    */
   public static boolean interceptDrop(ServerPlayer player, ItemStack stack) {
      if (!ProtectedItems.isProtected(stack)) {
         return false;
      }

      ItemStack restored = stack.copy();
      Inventory inventory = player.getInventory();
      inventory.add(inventory.getSelectedSlot(), restored);
      if (!restored.isEmpty()) {
         inventory.add(restored);
      }

      if (!restored.isEmpty()) {
         OneShotOneKill.INSTANCE.getLOGGER().error("Geschütztes Item von {} konnte nach Drop nicht wiederhergestellt werden: {}",
            player.getGameProfile().name(), restored.getHoverName().getString());
      }
      player.containerMenu.broadcastChanges();
      Feedback.actionBar(player, DROP_MESSAGE);
      return true;
   }

   /**
    * Ersetzt NeoForges {@code LivingSwapItemsEvent.Hands}.
    *
    * Aufgerufen aus {@code ServerGamePacketListenerSwapMixin}.
    *
    * @return {@code true}, wenn der Handtausch nicht stattfinden darf
    */
   public static boolean blocksHandSwap(ServerPlayer player) {
      if (ProtectedItems.isSlotLocked(player.getMainHandItem())
         || ProtectedItems.isSlotLocked(player.getOffhandItem())) {
         Feedback.actionBar(player, LOCK_MESSAGE);
         return true;
      }
      return false;
   }

   /**
    * Liefert dem OneShot-Bogen virtuelle Munition.
    *
    * Ersetzt NeoForges {@code LivingGetProjectileEvent}; aufgerufen aus
    * {@code PlayerProjectileMixin}, also auf Client und Server. Ein jeweils neuer Stack
    * verhindert Desynchronisationen und liegt nie im Inventar; Infinity macht den erzeugten
    * Pfeil zugleich unaufhebbar. Andere Vanilla-Bögen behalten ihre normale Munitionspflicht.
    *
    * @return der virtuelle Pfeil, oder {@code null}, wenn Vanilla weiterrechnen soll
    */
   public static @Nullable ItemStack virtualProjectile(ItemStack weapon) {
      if (weapon.is(Items.BOW) && ProtectedItems.isProtected(weapon)) {
         return new ItemStack(Items.ARROW);
      }
      return null;
   }
   /**
    * Entscheidet, ob ein Klick im Inventar fallen muss.
    *
    * <p>Ersetzt den früheren {@code GuardedSlot}. Der ersetzte jeden Slot eines Menüs durch eine
    * Hülle, die {@code mayPickup} und {@code mayPlace} verneinte – aber nur auf dem Server. Der
    * Client kannte die Hülle nicht, sagte den Zug voraus und bekam ihn im nächsten
    * Inventarpaket zurückgenommen: Der Dolch ließ sich herausziehen und sprang sichtbar wieder
    * an seinen Platz.</p>
    *
    * <p>Diese Prüfung hängt stattdessen in {@code AbstractContainerMenuClickMixin} am Anfang von
    * {@code AbstractContainerMenu#clicked}, und die ruft Vanilla auf beiden Seiten auf – einmal
    * aus {@code MultiPlayerGameMode}, einmal aus {@code ServerGamePacketListenerImpl}. Damit
    * fällt der Klick, bevor überhaupt etwas bewegt oder vorausgesagt wird.</p>
    *
    * @param slotIndex angeklickter Slot; {@code -999} steht für „neben das Fenster geklickt“
    * @param buttonNum bei {@link ContainerInput#SWAP} der Hotbar-Slot (0–8) oder 40 für die Zweithand
    * @return {@code true}, wenn der Klick nicht ausgeführt werden darf
    */
   public static boolean blocksContainerClick(AbstractContainerMenu menu, int slotIndex, int buttonNum,
                                              ContainerInput input, Player player) {
      // Neben das Fenster: der getragene Stapel fiele zu Boden.
      if (slotIndex == -999) {
         return ProtectedItems.isProtected(menu.getCarried()) && refuse(player, DROP_MESSAGE);
      }
      if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
         return false;
      }

      Slot slot = menu.slots.get(slotIndex);
      ItemStack inSlot = slot.getItem();
      ItemStack swapped = input == ContainerInput.SWAP ? swapSource(player, buttonNum) : ItemStack.EMPTY;

      // Feste Ausrüstung bleibt in ihrem Slot, über jeden Klickweg.
      //
      // Ausgenommen ist allein das Ziehen über mehrere Slots: Dessen Zustandsmaschine läuft über
      // drei Klicks, und ein verworfener Abschlussklick ließe sie halb offen stehen. Nötig ist
      // die Ausnahme ohnehin nicht – ein gebundener Gegenstand kann nie der getragene Stapel
      // sein, und Dolch und Bogen stapeln nicht, können also auch nichts aufnehmen.
      if (input != ContainerInput.QUICK_CRAFT
         && (ProtectedItems.isSlotLocked(inSlot) || ProtectedItems.isSlotLocked(swapped))) {
         return refuse(player, LOCK_MESSAGE);
      }

      // Nichts Geschütztes wegwerfen (Wurftaste im geöffneten Inventar).
      if (input == ContainerInput.THROW && ProtectedItems.isProtected(inSlot)) {
         return refuse(player, DROP_MESSAGE);
      }

      // Spezial-Items bleiben im eigenen Inventar und wandern in keinen Fremdslot.
      if (slot.container != player.getInventory()
         && (ProtectedItems.isSpecialItem(menu.getCarried()) || ProtectedItems.isSpecialItem(swapped))) {
         return refuse(player, STASH_MESSAGE);
      }
      // Umlagern per Umschalt-Klick geht im eigenen Inventarmenü zwischen Hotbar und Fächern hin
      // und her; in jedem anderen Menü führte es in den fremden Behälter.
      if (input == ContainerInput.QUICK_MOVE && ProtectedItems.isSpecialItem(inSlot)
         && menu != player.inventoryMenu) {
         return refuse(player, STASH_MESSAGE);
      }
      return false;
   }

   /** Bei {@link ContainerInput#SWAP} der Gegenstand, der aus Hotbar oder Zweithand käme. */
   private static ItemStack swapSource(Player player, int buttonNum) {
      boolean hotbar = buttonNum >= 0 && buttonNum < Inventory.getSelectionSize();
      if (hotbar || buttonNum == Inventory.SLOT_OFFHAND) {
         return player.getInventory().getItem(buttonNum);
      }
      return ItemStack.EMPTY;
   }

   /**
    * Verwirft den Klick und sagt auf dem Server, warum.
    *
    * Die Prüfung läuft auf beiden Seiten; die Einblendung ist ein Paket und darf deshalb nur
    * vom Server ausgehen.
    */
   private static boolean refuse(Player player, String message) {
      if (player instanceof ServerPlayer serverPlayer) {
         Feedback.actionBar(serverPlayer, message);
      }
      return true;
   }


   
   
   /**
    * Rechtsklick mit leerer Hand, C4 oder Zünder auf eine Haftladung nimmt diese ab,
    * ohne dass der Bogen in der Zweithand aufgespannt wird.

   /**
    * Rechtsklick mit leerer Hand, C4 oder Zünder auf eine Haftladung nimmt diese ab,
    * ohne dass der Bogen in der Zweithand aufgespannt wird.
    *
    * Die beiden Klickwege laufen über Fabrics Callbacks. Für den Beginn der Item-Nutzung und
    * für Spannen und Lösen des Bogens gibt es kein Fabric-Ereignis; die drei bedient
    * {@link InteractionGates} aus einem Mixin heraus.
    */
   public static final class ChargeInteractionEvents {
      /** So viele Ticks nach einer Abnahme bleibt der Bogen gesperrt. */
      private static final int GUARD_TICKS = 10;

      /** Spieler-UUID -> Tick, bis zu dem Bogenbenutzung und Klicks noch verworfen werden. */
      private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();

      private ChargeInteractionEvents() {
      }

      public static void register() {
         UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (consumesClick(player)) {
               player.stopUsingItem();
               return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
         });
         UseItemCallback.EVENT.register((player, level, hand) -> {
            if (isGuardedOrAiming(player)) {
               player.stopUsingItem();
               return InteractionResult.FAIL;
            }
            if (consumesClick(player)) {
               player.stopUsingItem();
               return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
         });
      }

      /**
       * Verhindert den Beginn der Item-Nutzung (Bogen-Spannen).
       *
       * @return {@code true}, wenn die Nutzung nicht beginnen darf
       */
      public static boolean blocksItemUseStart(LivingEntity entity, ItemStack stack) {
         if (entity instanceof Player player && stack.is(Items.BOW) && isGuardedOrAiming(player)) {
            player.stopUsingItem();
            return true;
         }
         return false;
      }

      /**
       * Verhindert das Aufspannen des Bogens beim Abnehmen von C4.
       *
       * @return {@code true}, wenn der Bogen nicht gespannt werden darf
       */
      public static boolean blocksBowDraw(Player player) {
         if (isGuardedOrAiming(player) || consumesClick(player)) {
            player.stopUsingItem();
            return true;
         }
         return false;
      }

      /**
       * Verhindert versehentliches Pfeillösen, falls der Bogen im selben Tick ausgelöst wurde.
       *
       * @return {@code true}, wenn kein Pfeil abgehen darf
       */
      public static boolean blocksBowRelease(Player player) {
         if (isGuardedOrAiming(player)) {
            player.stopUsingItem();
            return true;
         }
         return false;
      }

      private static boolean canRecoverWithHand(Player player) {
         return player.getMainHandItem().isEmpty() || player.getMainHandItem().is(ModItems.C4);
      }
   
      private static boolean isAimingAtC4DisplayClient(Player player) {
         Vec3 eye = player.getEyePosition();
         Vec3 look = player.getLookAngle();
         AABB box = new AABB(eye.x - 6.0, eye.y - 6.0, eye.z - 6.0, eye.x + 6.0, eye.y + 6.0, eye.z + 6.0);
         for (Display.ItemDisplay display : player.level().getEntitiesOfClass(Display.ItemDisplay.class, box)) {
            ItemStack stack = display.getSlot(0).get();
            if (!stack.is(ModItems.C4_CHARGE) && !stack.is(ModItems.C4)) {
               continue;
            }
            Vec3 toDisplay = display.position().subtract(eye);
            double along = toDisplay.dot(look);
            if (along >= 0.0 && along <= 4.5 && toDisplay.subtract(look.scale(along)).length() <= 0.6) {
               return true;
            }
         }
         return false;
      }
   
      private static boolean isGuardedOrAiming(Player player) {
         long now = player.level().getGameTime();
         GUARD_UNTIL.entrySet().removeIf(entry -> entry.getValue() < now);
         if (GUARD_UNTIL.containsKey(player.getUUID())) {
            return true;
         }
         if (!canRecoverWithHand(player)) {
            return false;
         }
         if (player.level().isClientSide()) {
            return isAimingAtC4DisplayClient(player);
         }
         if (player instanceof ServerPlayer serverPlayer) {
            return Deployables.INSTANCE.isAimingAtCharge(serverPlayer);
         }
         return false;
      }
   
      /**
       * @return true, wenn dieser Klick dem Abnehmen einer Ladung gilt und nirgendwo sonst ankommen darf.
       */
      private static boolean consumesClick(Player player) {
         long now = player.level().getGameTime();
         GUARD_UNTIL.entrySet().removeIf(entry -> entry.getValue() < now);
   
         if (canRecoverWithHand(player)) {
            if (player.level().isClientSide()) {
               if (isAimingAtC4DisplayClient(player)) {
                  player.stopUsingItem();
                  GUARD_UNTIL.put(player.getUUID(), now + GUARD_TICKS);
                  return true;
               }
            } else if (player instanceof ServerPlayer serverPlayer) {
               if (Deployables.INSTANCE.recoverAimedCharge(serverPlayer)) {
                  serverPlayer.stopUsingItem();
                  GUARD_UNTIL.put(serverPlayer.getUUID(), now + GUARD_TICKS);
                  return true;
               }
            }
         }
         if (GUARD_UNTIL.containsKey(player.getUUID())) {
            player.stopUsingItem();
            return true;
         }
         return false;
      }
   }
}
