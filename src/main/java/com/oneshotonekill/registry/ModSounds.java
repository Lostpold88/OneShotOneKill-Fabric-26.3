package com.oneshotonekill.registry;

import com.oneshotonekill.OneShotOneKill;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Eigene Klänge der Mod. */
public final class ModSounds {
    public static final SoundEvent BOOGIE_BOMB = register("items.boogie_bomb");

    public static final SoundEvent MINIGUN = register("items.minigun");
   /**
    * Die Ansage zum Matchende – das Original aus Call of Duty.
    * <p>
    * Sie gibt den Takt der ganzen Sequenz vor: Der Einschlag liegt bei 12,031 Sekunden in der
    * Aufnahme, und {@code NukePhase.DETONATION} ist genau darauf gelegt. Abgespielt wird sie
    * clientseitig ohne Abstandsdämpfung, siehe {@code client/sound/NukeSoundController}.
    */
   public static final SoundEvent NUKE_INCOMING = register("endgame.tactical_nuke_incoming");
   /**
    * Das synthetisierte Bett unter der Ansage: Herzschlag, Sub-Drone, Sirenenheulen, Riser.
    * Startet im selben Tick wie {@link #NUKE_INCOMING} und ist auf dieselben 12,05 Sekunden gelegt.
    * Erzeugt von {@code tools/audio/generate_nuke_audio.py}.
    */
   public static final SoundEvent NUKE_BED = register("endgame.nuke_countdown_bed");
   /** Der Einschlag selbst: Knall, Sub-Boom, Druckwelle, Trümmerregen, Tinnitus. */
   public static final SoundEvent NUKE_IMPACT = register("endgame.nuke_impact");
   /** Nahtloser Loop für den Nachlauf: Wind, Drone, Feuerknistern. */
   public static final SoundEvent NUKE_FALLOUT = register("endgame.nuke_fallout_loop");
   /** Fanfare zur Siegerehrung. */
   public static final SoundEvent NUKE_VICTORY = register("endgame.nuke_victory");

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
