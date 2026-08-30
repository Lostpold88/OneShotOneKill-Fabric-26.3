package com.oneshotonekill.registry;

import com.oneshotonekill.OneShotOneKill;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Unit;

/** Registriert die benutzerdefinierten Data-Components der Mod. */
public final class ModDataComponents {
   /**
    * Markiert einen C4-Gegenstand im Inventar als scharfen Zuender fuer eine bereits platzierte Ladung.
    * <p>
    * Gegenstaende ohne dieses Component werden als unplatzierte C4-Sprengstoffriegel gerendert.
    */
   public static final DataComponentType<Unit> C4_ARMED = Registry.register(
      BuiltInRegistries.DATA_COMPONENT_TYPE,
      OneShotOneKill.INSTANCE.id("c4_armed"),
      DataComponentType.<Unit>builder()
         .persistent(Unit.CODEC)
         .networkSynchronized(Unit.STREAM_CODEC)
         .build());

   private ModDataComponents() {
   }

   /** Löst das Laden dieser Klasse und damit die Registrierung aus. */
   public static void register() {
   }
}
