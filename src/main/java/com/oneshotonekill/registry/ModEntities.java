package com.oneshotonekill.registry;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.entity.OwnerVisibleItemDisplay;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Serverseitig gefilterte Display-Typen der Mod. */
public final class ModEntities {
   public static final EntityType<OwnerVisibleItemDisplay> OWNER_VISIBLE_ITEM_DISPLAY =
      register("owner_visible_item_display",
         EntityType.Builder.<OwnerVisibleItemDisplay>of(OwnerVisibleItemDisplay::new, MobCategory.MISC)
            .noLootTable().sized(0.0F, 0.0F).clientTrackingRange(10).updateInterval(1)
            .noSave().noSummon());

   private ModEntities() {
   }

   /** Löst das Laden dieser Klasse und damit die Registrierung aus. */
   public static void register() {
   }

   private static <T extends net.minecraft.world.entity.Entity> EntityType<T> register(
      String name, EntityType.Builder<T> builder) {
      ResourceKey<EntityType<?>> key =
         ResourceKey.create(Registries.ENTITY_TYPE, OneShotOneKill.INSTANCE.id(name));
      return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
   }
}
