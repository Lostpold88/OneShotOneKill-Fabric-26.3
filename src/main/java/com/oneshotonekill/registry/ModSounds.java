package com.oneshotonekill.registry;

import com.oneshotonekill.OneShotOneKill;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Eigene Klänge der Mod. */
public final class ModSounds {
   public static final SoundEvent MINIGUN = register("items.minigun");
   /**
    * Die Ansage zum Matchende – das Original aus Call of Duty.
    *
    * Sie gibt den Takt der ganzen Sequenz vor: Der Einschlag liegt bei 12,031 Sekunden in der
    * Aufnahme, und {@code NukePhase.DETONATION} ist genau darauf gelegt. Abgespielt wird sie
    * clientseitig ohne Abstandsdämpfung, siehe {@code client/sound/NukeSoundController}.
    */
   public static final SoundEvent NUKE_INCOMING = register("endgame.tactical_nuke_incoming");

   private ModSounds() {
   }

   /** Löst das Laden dieser Klasse und damit die Registrierung aus. */
   public static void register() {
   }

   private static SoundEvent register(String path) {
      Identifier id = OneShotOneKill.INSTANCE.id(path);
      return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
   }
}
