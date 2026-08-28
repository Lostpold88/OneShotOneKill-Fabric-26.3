package com.oneshotonekill.registry;

import com.oneshotonekill.OneShotOneKill;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * Eigene Schadensarten der Mod.
 *
 * <p>Schadensarten sind seit 1.19.4 Datenpaket-Inhalt und werden nicht mehr im Code angelegt.
 * Hier steht deshalb nur der Schlüssel; die Beschreibung liegt in
 * {@code data/oneshotonekill/damage_type/nuke_blast.json}, und was sie durchdringt, steht in
 * den Tag-Dateien unter {@code data/minecraft/tags/damage_type/}. Dort ist
 * {@code nuke_blast} allen Bypass-Tags zugeordnet, die es gibt: Rüstung, Verzauberungen,
 * Widerstands-Effekt und Unverwundbarkeit.</p>
 *
 * <p>Das letzte davon ist das wichtige. Ohne {@code bypasses_invulnerability} hielte ein
 * Totem den Einschlag auf, und die Nuke wäre die einzige Fähigkeit im Spiel, gegen die es ein
 * Gegenmittel gibt – dabei ist sie das Ende des Matches und keine Waffe. Aus demselben Grund
 * greift auch die Unverwundbarkeit nicht, die {@code NukeSequenceManager} während des
 * Countdowns über alle Spieler legt: Sie soll das Gefecht einfrieren, nicht den Einschlag
 * verhindern.</p>
 */
public final class ModDamageTypes {
   public static final ResourceKey<DamageType> NUKE_BLAST =
      ResourceKey.create(Registries.DAMAGE_TYPE, OneShotOneKill.INSTANCE.id("nuke_blast"));

   private ModDamageTypes() {
   }

   /**
    * Die Schadensquelle des Einschlags.
    *
    * Ohne Verursacher: Die Nuke gehört keinem. Sie beendet das Match, und wer sie ausgelöst
    * hat, steht schon in der Siegermeldung – ihn zusätzlich als Töter in jede Todesmeldung zu
    * schreiben, machte aus einem Matchende eine Serie von Kills.
    */
   public static DamageSource nukeBlast(ServerLevel level) {
      return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(NUKE_BLAST));
   }
}
