package com.oneshotonekill.item.types;

import com.oneshotonekill.shared.SpecialItemRules;

import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.shared.DeviceLights;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.item.runtime.RailgunSystem;
import com.oneshotonekill.registry.ModDataComponents;
import com.oneshotonekill.registry.ModItems;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Klassensammlung aller schweren Spezial-Waffen (Minigun, Railgun, C4).
 */
@SuppressWarnings("NullableProblems")
public final class WeaponItems {
   private WeaponItems() {}

   // --- MinigunItem.java ---
   public static final class MinigunItem extends Item {
      public static final int ARROWS_PER_TICK = 2;
      public static final float ARROW_SPEED = 5.2F;
      public static final float INACCURACY = 0.0F;
      public static final int WARM_UP_TICKS = 15;
   
      private static final double MUZZLE_FLASH_SPEED = 0.32;
      private static final double MUZZLE_SPARK_SPEED = 0.55;
      private static final int SMOKE_EVERY_NTH_SHOT = 5;
   
      public MinigunItem(Properties properties) {
         super(properties);
      }
   
      @Override
      public InteractionResult use(Level level, Player player, InteractionHand hand) {
         if (player instanceof ServerPlayer serverPlayer && !MinigunRuntime.INSTANCE.begin(serverPlayer)) {
            return InteractionResult.FAIL;
         }
         player.startUsingItem(hand);
         return InteractionResult.CONSUME;
      }
   
      @Override
      public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingUseDuration) {
         if (!(level instanceof ServerLevel serverLevel) || !(user instanceof ServerPlayer player) || !MinigunRuntime.INSTANCE.canFire(player)) {
            return;
         }
         if (MinigunRuntime.INSTANCE.elapsedUseTicks(player) >= WARM_UP_TICKS) {
            for (int index = 0; index < ARROWS_PER_TICK; index++) {
               fireArrow(serverLevel, player, stack);
            }
         }
      }
   
      @Override
      public int getUseDuration(ItemStack stack, LivingEntity entity) {
         return 72_000;
      }
   
      @Override
      public ItemUseAnimation getUseAnimation(ItemStack stack) {
         return ItemUseAnimation.NONE;
      }
   
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      /*
       * NeoForge brauchte hier ein canContinueUsing, das die Farbänderung der Anzeigen von einem
       * echten Waffenwechsel unterschied. Vanilla vergleicht in LivingEntity#updatingUsingItem
       * ohnehin nur mit ItemStack.isSameItem, also allein den Gegenstandstyp - die Nutzung läuft
       * über jede Komponentenänderung hinweg weiter, ohne dass etwas überschrieben werden muss.
       */
   
      private void fireArrow(ServerLevel level, ServerPlayer player, ItemStack weapon) {
         MinigunRuntime.Muzzle.Shot shot = MinigunRuntime.Muzzle.resolve(level, player);
         Vec3 muzzleOrigin = shot.origin();
         Vec3 direction = shot.direction();
   
         Arrow arrow = new Arrow(level, player, new ItemStack(Items.ARROW), weapon);
         arrow.pickup = Pickup.DISALLOWED;
         arrow.setCritArrow(true);
         arrow.setNoGravity(true);
         arrow.setPos(muzzleOrigin.x, muzzleOrigin.y, muzzleOrigin.z);
         arrow.shoot(direction.x, direction.y, direction.z, ARROW_SPEED, INACCURACY);
         level.addFreshEntity(arrow);
         MinigunRuntime.INSTANCE.trackArrow(player, arrow);
   
         spawnMuzzleFlash(level, muzzleOrigin, direction);
   
         // Kein Klang je Schuss mehr. Den trägt der eigene Dauerlauf aus items/minigun.ogg, und
         // den hört jetzt auch, wer danebensteht – siehe client/sound/MinigunSoundController.
         // Zwei Pfeilgeräusche je Tick darüberzulegen übertönte ihn nur.
      }
   
      /**
       * Stichflamme und Funken fahren in Schussrichtung aus dem Lauf, statt an der Mündung zu stehen.
       * <p>
       * Bei einer Stückzahl von null gilt der Richtungsvektor als Geschwindigkeit – so bekommt der
       * Blitz eine Richtung, ohne dass dafür ein eigener Partikeltyp nötig wäre.
       */
      private static void spawnMuzzleFlash(ServerLevel level, Vec3 origin, Vec3 direction) {
         level.sendParticles(ParticleTypes.FLAME, origin.x, origin.y, origin.z, 0,
            direction.x, direction.y, direction.z, MUZZLE_FLASH_SPEED);
         level.sendParticles(ParticleTypes.CRIT, origin.x, origin.y, origin.z, 0,
            direction.x, direction.y, direction.z, MUZZLE_SPARK_SPEED);
         if (level.getRandom().nextInt(SMOKE_EVERY_NTH_SHOT) == 0) {
            level.sendParticles(ParticleTypes.SMOKE, origin.x, origin.y, origin.z, 1, 0.01, 0.02, 0.01, 0.005);
         }
      }
   }

   // --- RailgunItem.java ---
   /**
    * Aufladen und loslassen – aber erst die volle Ladung schießt.
    * <p>
    * Sie baut deshalb nicht auf {@link AbilityItems.SpecialAbilityItem} auf – der verbraucht das Item schon
    * beim Drücken, hier fällt die Entscheidung aber erst beim Loslassen. Wer zu früh loslässt,
    * behält seine Railgun: ein Fehlversuch soll nichts kosten, sonst traut sich niemand, das
    * Aufladen überhaupt auszuprobieren.
    */
   public static final class RailgunItem extends Item {
      public RailgunItem(Properties properties) {
         super(properties);
      }
   
      @Override
      public InteractionResult use(Level level, Player player, InteractionHand hand) {
         if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            // Der Client muss ebenfalls anfangen, sonst läuft die Haltung der Hand nicht mit.
            player.startUsingItem(hand);
            return InteractionResult.CONSUME;
         }
         if (!SpecialItemRules.canUseOrExplain(serverPlayer)) {
            return InteractionResult.FAIL;
         }
   
         player.startUsingItem(hand);
         RailgunSystem.INSTANCE.beginCharge(serverLevel, serverPlayer, player.getItemInHand(hand));
         return InteractionResult.CONSUME;
      }
   
      @Override
      public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingUseDuration) {
         if (level instanceof ServerLevel serverLevel && user instanceof ServerPlayer player) {
            RailgunSystem.INSTANCE.chargeTick(serverLevel, player, stack);
         }
      }
   
      @Override
      public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTime) {
         if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player) {
            int elapsed = getUseDuration(stack, entity) - remainingTime;
            if (RailgunSystem.INSTANCE.release(serverLevel, player, stack, elapsed)) {
               stack.shrink(1);
            }
         }
         return true;
      }
   
      /**
       * Lässt die Anzeigen der Waffe auch dann blinken, wenn niemand lädt.
       * <p>
       * Nur in der Haupthand: {@code inventoryTick} läuft für jeden Gegenstand in jedem Inventar,
       * und eine Railgun tief in der Tasche muss niemandem etwas anzeigen.
       */
      @Override
      public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
         if (slot == EquipmentSlot.MAINHAND && !(owner instanceof LivingEntity user && user.isUsingItem())) {
            RailgunSystem.INSTANCE.idleBlink(stack, level.getGameTime());
         }
      }
   
      /**
       * Verhindert, dass die Waffe bei jedem Blinken neu in die Hand genommen wird.
       * <p>
       * Vanilla vergleicht die beiden Stäpel über die Objektgleichheit
       * ({@code IItemExtension#shouldCauseReequipAnimation} gibt {@code oldStack != newStack}
       * zurück). Jede Farbänderung erzeugt clientseitig einen neuen Stapel und gälte damit als
       * Waffenwechsel – die Railgun würde beim Blinken und beim Laden ständig heruntergenommen
       * und wieder hochgerissen.
       */
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      /*
       * NeoForge brauchte hier ein canContinueUsing, das die Farbänderung der Anzeigen von einem
       * echten Waffenwechsel unterschied. Vanilla vergleicht in LivingEntity#updatingUsingItem
       * ohnehin nur mit ItemStack.isSameItem, also allein den Gegenstandstyp - die Nutzung läuft
       * über jede Komponentenänderung hinweg weiter, ohne dass etwas überschrieben werden muss.
       */
   
      @Override
      public int getUseDuration(ItemStack stack, LivingEntity entity) {
         return 72_000;
      }
   
      @Override
      public ItemUseAnimation getUseAnimation(ItemStack stack) {
         return ItemUseAnimation.NONE;
      }
   }

   // --- C4Item.java ---
   /**
    * Haftladung und Zünder in einem Gegenstand.
    * <p>
    * Anders als Frost-Falle und Geschützturm baut die Ladung nicht auf {@link DeployableItems.PlacedSpecialItem}
    * auf. Zwei Gründe: Sie braucht die angeklickte Fläche, um sich richtig herum daran zu drehen,
    * und sie darf ausdrücklich auch nach unten – eine Ladung unter einer Decke oder an einer Wand
    * ist der halbe Reiz an ihr, und genau das schließt die gemeinsame Basis aus.
    * <p>
    * <p>Der Fernzünder als eigener Gegenstand ist weg. Er belegte einen zweiten Platz in einer
    * ohnehin knappen Leiste und hatte ohne Ladung keine Funktion; jetzt bedient das C4 sich
    * selbst. Sein <em>Modell</em> ist dagegen geblieben und sitzt nun auf diesem Gegenstand: Was
    * man in der Hand hält, ist der flache Zündkasten aus
    * {@code tools/generate_field_gear_3d.py}. Die Sprengladung sieht anders aus, weil sie etwas
    * anderes ist – sie klebt an der Wand und trägt das Modell von
    * {@code ModItems.C4_CHARGE}.</p>
    * <p>
    * <ul>
    *   <li><b>Auf einen Block:</b> die Ladung klebt dort. Der Gegenstand bleibt dabei in der Hand –
    *       er ist ja der Zünder. Verbraucht wird er erst beim Zünden.</li>
    *   <li><b>In die Luft oder geschlichen:</b> alle eigenen Ladungen gehen gleichzeitig hoch.</li>
    *   <li><b>Auf eine klebende Ladung:</b> sie kommt wieder ab – das erledigt
    *       {@code event.ChargeInteractionEvents}, damit es auch mit leerer Hand geht.</li>
    * </ul>
    */
   public static final class C4Item extends Item {
      /**
       * Das Statusband des Zündkastens: ruhiges Bernstein ohne Ladung, hektisches Rot mit.
       * <p>
       * Der Takt hängt an der Zahl der scharfen Ladungen – je mehr, desto schneller. Damit sagt
       * das Gerät auf einen Blick, ob überhaupt etwas zu zünden ist.
       */
      private static final int BAND_IDLE = 0xFFA43A;
      private static final int BAND_ARMED = 0xFF3B2A;
      private static final int BAND_DIM = 0x3A0E08;
   
      public C4Item(Properties properties) {
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
         if (slot != EquipmentSlot.MAINHAND || !(owner instanceof ServerPlayer player)) {
            return;
         }
         boolean armed = stack.has(ModDataComponents.C4_ARMED);
         if (!armed) {
            DeviceLights.beacon(stack, level.getGameTime(), BAND_IDLE, BAND_DIM);
         } else {
            int charges = Deployables.INSTANCE.chargeCount(player);
            DeviceLights.strobe(stack, level.getGameTime(), Math.max(4, 14 - charges * 3), BAND_ARMED, BAND_DIM);
         }
      }
   
      @Override
      public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
         return DeviceLights.allowsReequipAnimation();
      }
   
      /**
       * Rechtsklick auf einen Block: kleben – geschlichen dagegen zünden.
       * <p>
       * Das Schleichen ist der zweite Weg zum Zünden neben dem Klick ins Leere. Ohne ihn stünde,
       * wer in einem Gang vor einer Wand steht, ohne Auslöser da: Sein Blick trifft von dort aus
       * überall einen Block.
       */
      @Override
      public InteractionResult useOn(UseOnContext context) {
         if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.CONSUME;
         }
         if (!SpecialItemRules.canUseOrExplain(player)) {
            return InteractionResult.CONSUME;
         }
         ItemStack stack = context.getItemInHand();
         if (stack.has(ModDataComponents.C4_ARMED) || player.isShiftKeyDown()) {
            Deployables.INSTANCE.detonateAll(player);
            return InteractionResult.CONSUME;
         }
   
         Direction face = context.getClickedFace();
         // Vor der Fläche muss Platz sein, sonst klebte die Ladung in einem Block.
         if (!level.getBlockState(context.getClickedPos().relative(face)).isAir()) {
            return InteractionResult.CONSUME;
         }
         if (!Deployables.INSTANCE.placeC4(level, player, context.getClickLocation(), face)) {
            return InteractionResult.CONSUME;
         }
   
         if (hasDetonator(player)) {
            // Der bereits vorhandene Zünder wird mitgenutzt, dieser weitere Gegenstand wird verbraucht
            stack.shrink(1);
         } else {
            // Die erste platzierte Ladung verwandelt diesen Gegenstand in den Zünder
            stack.set(ModDataComponents.C4_ARMED, net.minecraft.util.Unit.INSTANCE);
         }
         player.containerMenu.broadcastChanges();
         return InteractionResult.CONSUME;
      }
   
      private static boolean hasDetonator(ServerPlayer player) {
         for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item.is(ModItems.C4) && item.has(ModDataComponents.C4_ARMED)) {
               return true;
            }
         }
         return false;
      }
   
      /**
       * Rechtsklick ins Leere: zünden.
       * <p>
       * Der Rückgabewert ist auch dann kein {@code PASS}, wenn nichts scharf ist. Sonst ginge der
       * Klick an die Zweithand weiter – und dort steckt der Bogen.
       */
      @Override
      public InteractionResult use(Level level, Player player, InteractionHand hand) {
         if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel)) {
            return InteractionResult.CONSUME;
         }
         if (!SpecialItemRules.canUseOrExplain(serverPlayer)) {
            return InteractionResult.CONSUME;
         }
         Deployables.INSTANCE.detonateAll(serverPlayer);
         return InteractionResult.CONSUME;
      }
   }
}
